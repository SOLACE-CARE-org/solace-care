import { type DB, open } from '@op-engineering/op-sqlite';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

/**
 * Entries logged on this phone and not yet accepted by Tracking (epic 9). They live in an
 * SQLCipher database whose key is in the Keychain or Keystore, so nothing logged is readable
 * from the phone's storage (System Design, Offline; RoPA P15). Nothing here is ever logged.
 */
export interface PendingRow {
  clientEntryId: string;
  /** The PushEntry as JSON. */
  body: string;
  savedAt: string;
}

const KEY_NAME = 'solace.tracking.db-key';
let db: DB | null = null;

async function key(): Promise<string> {
  const existing = await SecureStore.getItemAsync(KEY_NAME);
  if (existing) return existing;
  const bytes = await Crypto.getRandomBytesAsync(32);
  const fresh = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  await SecureStore.setItemAsync(KEY_NAME, fresh, {
    keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
  });
  return fresh;
}

async function database(): Promise<DB> {
  if (db) return db;
  const opened = open({ name: 'tracking.sqlite', encryptionKey: await key() });
  await opened.execute(
    `CREATE TABLE IF NOT EXISTS pending_entry (
       client_entry_id TEXT PRIMARY KEY,
       body TEXT NOT NULL,
       saved_at TEXT NOT NULL
     )`,
  );
  // The catalogue and element labels, for logging offline (epic 9). Which modules someone tracks
  // is health information, so it is kept here, encrypted, not in AsyncStorage.
  await opened.execute(
    'CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, body TEXT NOT NULL, saved_at TEXT NOT NULL)',
  );
  db = opened;
  return opened;
}

export const cacheStore = {
  async get(key: string): Promise<string | null> {
    const d = await database();
    const result = await d.execute('SELECT body FROM cache WHERE key = ?', [key]);
    const row = result.rows[0];
    return row ? String(row.body) : null;
  },

  async set(key: string, body: string): Promise<void> {
    const d = await database();
    await d.execute('INSERT OR REPLACE INTO cache (key, body, saved_at) VALUES (?, ?, ?)', [
      key,
      body,
      new Date().toISOString(),
    ]);
  },

  async clear(): Promise<void> {
    const d = await database();
    await d.execute('DELETE FROM cache');
  },
};

export const pendingStore = {
  async put(rows: PendingRow[]): Promise<void> {
    const d = await database();
    await d.transaction(async (tx) => {
      for (const r of rows) {
        await tx.execute(
          'INSERT OR REPLACE INTO pending_entry (client_entry_id, body, saved_at) VALUES (?, ?, ?)',
          [r.clientEntryId, r.body, r.savedAt],
        );
      }
    });
  },

  async all(): Promise<PendingRow[]> {
    const d = await database();
    const result = await d.execute(
      'SELECT client_entry_id, body, saved_at FROM pending_entry ORDER BY saved_at',
    );
    return result.rows.map((r) => ({
      clientEntryId: String(r.client_entry_id),
      body: String(r.body),
      savedAt: String(r.saved_at),
    }));
  },

  async remove(ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    const d = await database();
    await d.transaction(async (tx) => {
      for (const id of ids) {
        await tx.execute('DELETE FROM pending_entry WHERE client_entry_id = ?', [id]);
      }
    });
  },

  /** Sign-out and account deletion: nothing logged stays on the phone. */
  async clear(): Promise<void> {
    const d = await database();
    await d.execute('DELETE FROM pending_entry');
  },
};
