import { env } from '@/config/env';

/** Tokens from Keycloak's members realm. */
export type Tokens = {
  accessToken: string;
  refreshToken: string;
  /** Epoch milliseconds when the access token stops working. */
  accessExpiresAt: number;
};

/**
 * Why a code was not accepted; the screen words it (StateCodeWrong). Keycloak answers the same way
 * whether or not the contact has an account.
 */
export class CodeRejected extends Error {
  constructor(
    /** Wrong entries still allowed on this code. */
    readonly attemptsLeft: number,
    /** That was the last try: a new code is on its way. */
    readonly newCodeSent: boolean,
    /** The code expired or was used: ask for a new one. */
    readonly expired: boolean,
  ) {
    super('code rejected');
  }
}

const tokenUrl = () =>
  `${env.keycloakUrl}/realms/${env.membersRealm}/protocol/openid-connect/token`;

async function post(form: Record<string, string>): Promise<Response> {
  return fetch(tokenUrl(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: env.clientId, ...form }).toString(),
  });
}

function tokensFrom(body: {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}): Tokens {
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    accessExpiresAt: Date.now() + body.expires_in * 1000,
  };
}

/**
 * Asks Keycloak to send a code to a phone number (E.164) or an email. Keycloak answers the same
 * way whether or not the contact has an account (401 code_required), so this never says which.
 */
export async function requestCode(contact: string): Promise<void> {
  const response = await post({ grant_type: 'password', username: contact });
  if (response.status === 401 || response.ok) return;
  throw new Error(`code request failed: ${response.status}`);
}

/** Exchanges the code for tokens. Throws {@link CodeRejected} for a wrong or expired code. */
export async function verifyCode(contact: string, code: string): Promise<Tokens> {
  const response = await post({ grant_type: 'password', username: contact, code });
  if (response.status === 401 || response.status === 400) {
    const body = (await response.json().catch(() => ({}))) as {
      attempts_left?: number;
      new_code_sent?: boolean;
      code_expired?: boolean;
    };
    throw new CodeRejected(
      body.attempts_left ?? 0,
      body.new_code_sent === true,
      body.code_expired === true,
    );
  }
  if (!response.ok) throw new Error(`code check failed: ${response.status}`);
  return tokensFrom(await response.json());
}

/** A new access token from the refresh token; null when the session has ended. */
export async function refresh(refreshToken: string): Promise<Tokens | null> {
  const response = await post({ grant_type: 'refresh_token', refresh_token: refreshToken });
  if (response.status === 400 || response.status === 401) return null;
  if (!response.ok) throw new Error(`refresh failed: ${response.status}`);
  return tokensFrom(await response.json());
}
