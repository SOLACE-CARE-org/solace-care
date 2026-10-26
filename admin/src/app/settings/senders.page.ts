import {
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  signal,
  viewChild,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { RouterLink } from "@angular/router";
import { TranslocoPipe, TranslocoService } from "@jsverse/transloco";

import {
  ApiProblem,
  CoreApi,
  type CoreSchemas,
  orThrow,
} from "../api/core-api";
import { StaffSession } from "../auth/staff-session";

type Sender = CoreSchemas["MessageSender"];

/**
 * SMS and email senders (D61), opened from Settings and flags. Operations look; a Super admin adds
 * and changes them with a reason, and can send one test message through a sender. Keys stay in
 * Vault: the screen shows only whether they are found.
 */
@Component({
  selector: "sl-message-senders",
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/settings" class="sl-meta">{{
      "admin.senders.back" | transloco
    }}</a>
    <h1 class="sl-title">{{ "admin.senders.title" | transloco }}</h1>
    <p class="sl-muted subtitle">{{ "admin.senders.subtitle" | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ "common.checkConnection" | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ "common.tryAgain" | transloco }}
        </button>
      </div>
    } @else if (senders(); as list) {
      <section class="sl-card" aria-labelledby="senders-title">
        <div class="head">
          <h2 id="senders-title" class="section">
            {{ "admin.senders.list" | transloco }}
          </h2>
          @if (canChange()) {
            <button type="button" class="sl-button" (click)="askNew()">
              {{ "admin.senders.add" | transloco }}
            </button>
          }
        </div>
        @if (!list.length) {
          <p class="sl-muted">{{ "admin.senders.empty" | transloco }}</p>
        }
        <ul class="rows">
          @for (s of list; track s.code) {
            <li class="row">
              <div>
                <p class="label">{{ s.displayName }} · {{ s.code }}</p>
                <p class="sl-meta">
                  {{ "admin.senders.channel." + s.channel | transloco }} ·
                  {{ "admin.senders.adapter." + s.adapter | transloco }} ·
                  {{
                    "admin.senders.priority" | transloco: { n: s.priority }
                  }}
                  ·
                  {{
                    (s.keysFound
                      ? "admin.providers.keysFound"
                      : "admin.providers.keysMissing"
                    ) | transloco
                  }}
                </p>
                @if (s.problem) {
                  <p class="sl-meta problem">{{ s.problem }}</p>
                }
              </div>
              <span
                class="sl-pill"
                [class.sl-pill--brand]="s.status === 'ACTIVE'"
                >{{ "admin.senders.status." + s.status | transloco }}</span
              >
              @if (canChange()) {
                <span class="buttons">
                  <button type="button" class="sl-button" (click)="askTest(s)">
                    {{ "admin.senders.test" | transloco }}
                  </button>
                  <button
                    type="button"
                    class="sl-button"
                    (click)="askChange(s)"
                  >
                    {{ "admin.settings.change" | transloco }}
                  </button>
                </span>
              } @else {
                <span></span>
              }
            </li>
          }
        </ul>
      </section>
    } @else {
      <p class="sl-card sl-muted">{{ "common.loading" | transloco }}</p>
    }

    <dialog #senderDialog class="sl-dialog" aria-labelledby="sender-title">
      <h2 id="sender-title" class="section">
        {{
          (editing() ? "admin.senders.change" : "admin.senders.add") | transloco
        }}
      </h2>
      @if (!editing()) {
        <label class="sl-field">
          <span>{{ "admin.providers.code" | transloco }}</span>
          <input class="sl-input" [(ngModel)]="code" maxlength="31" />
        </label>
        <label class="sl-field">
          <span>{{ "admin.senders.channelLabel" | transloco }}</span>
          <select class="sl-input" [(ngModel)]="channel">
            <option value="SMS">
              {{ "admin.senders.channel.SMS" | transloco }}
            </option>
            <option value="EMAIL">
              {{ "admin.senders.channel.EMAIL" | transloco }}
            </option>
          </select>
        </label>
      } @else {
        <label class="sl-field">
          <span>{{ "admin.providers.statusLabel" | transloco }}</span>
          <select class="sl-input" [(ngModel)]="status">
            @for (s of statuses; track s) {
              <option [value]="s">
                {{ "admin.senders.status." + s | transloco }}
              </option>
            }
          </select>
        </label>
      }
      <label class="sl-field">
        <span>{{ "admin.providers.name" | transloco }}</span>
        <input class="sl-input" [(ngModel)]="displayName" maxlength="80" />
      </label>
      <label class="sl-field">
        <span>{{ "admin.senders.priorityLabel" | transloco }}</span>
        <input
          class="sl-input"
          type="number"
          min="1"
          max="10"
          [(ngModel)]="priority"
        />
      </label>
      <label class="sl-field">
        <span>{{ "admin.providers.secretRef" | transloco }}</span>
        <input class="sl-input" [(ngModel)]="secretRef" maxlength="63" />
      </label>
      <label class="sl-field">
        <span>{{ "admin.senders.settings" | transloco }}</span>
        <textarea
          class="sl-textarea mono"
          rows="8"
          [(ngModel)]="settings"
        ></textarea>
      </label>
      <p class="sl-meta">
        {{
          ((editing()?.channel ?? channel()) === "SMS"
            ? "admin.senders.smsHelp"
            : "admin.senders.emailHelp"
          ) | transloco
        }}
      </p>
      <label class="sl-field">
        <span>{{ "admin.bookings.reasonRequired" | transloco }}</span>
        <textarea
          class="sl-textarea"
          rows="3"
          maxlength="500"
          [(ngModel)]="reason"
        ></textarea>
      </label>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="senderDialog.close()">
          {{ "admin.common.cancel" | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="
            busy() ||
            !reason().trim() ||
            !displayName().trim() ||
            !secretRef().trim()
          "
          (click)="save()"
        >
          {{ "admin.common.save" | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #testDialog class="sl-dialog" aria-labelledby="test-title">
      <h2 id="test-title" class="section">
        {{ "admin.senders.test" | transloco }}
      </h2>
      <p class="sl-muted">{{ "admin.senders.testHelp" | transloco }}</p>
      <label class="sl-field">
        <span>{{ "admin.senders.to" | transloco }}</span>
        <input class="sl-input" [(ngModel)]="to" maxlength="254" />
      </label>
      @if (testResult(); as r) {
        <p class="sl-meta" role="status">
          {{
            (r.ok ? "admin.providers.testOk" : "admin.providers.testFailed")
              | transloco
          }}
          @if (r.problem) {
            · {{ r.problem }}
          }
        </p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="testDialog.close()">
          {{ "admin.common.cancel" | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !to().trim()"
          (click)="sendTest()"
        >
          {{ "admin.senders.send" | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-12);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 8rem auto;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row p {
      margin: 0;
    }
    .label {
      font-weight: 600;
    }
    .buttons {
      display: flex;
      gap: var(--space-8);
    }
    .mono {
      font-family: var(--font-mono, monospace);
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class MessageSendersPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly senderDialog =
    viewChild<ElementRef<HTMLDialogElement>>("senderDialog");
  private readonly testDialog =
    viewChild<ElementRef<HTMLDialogElement>>("testDialog");

  readonly statuses = ["DRAFT", "ACTIVE", "DISABLED"];
  readonly senders = signal<Sender[] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly editing = signal<Sender | null>(null);
  readonly testing = signal<Sender | null>(null);
  readonly testResult = signal<{ ok: boolean; problem?: string } | null>(null);
  readonly code = signal("");
  readonly channel = signal<"SMS" | "EMAIL">("SMS");
  readonly status = signal("DRAFT");
  readonly displayName = signal("");
  readonly priority = signal(1);
  readonly secretRef = signal("");
  readonly settings = signal("{}");
  readonly reason = signal("");
  readonly to = signal("");
  readonly canChange = computed(() => this.staff.accessTo("settings") === "F");

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.senders.set(
        orThrow(await this.api.client.GET("/admin/v1/message-senders")),
      );
    } catch {
      this.error.set(true);
    }
  }

  askNew() {
    this.editing.set(null);
    this.code.set("");
    this.channel.set("SMS");
    this.displayName.set("");
    this.priority.set(1);
    this.secretRef.set("");
    this.settings.set(
      JSON.stringify(
        {
          url: "https://",
          format: "JSON",
          fields: { to: "{to}", text: "{text}", from: "{from}" },
        },
        null,
        2,
      ),
    );
    this.open(this.senderDialog());
  }

  askChange(s: Sender) {
    this.editing.set(s);
    this.status.set(s.status);
    this.displayName.set(s.displayName);
    this.priority.set(s.priority);
    this.secretRef.set(s.secretRef);
    this.settings.set(JSON.stringify(s.settings ?? {}, null, 2));
    this.open(this.senderDialog());
  }

  askTest(s: Sender) {
    this.testing.set(s);
    this.to.set("");
    this.testResult.set(null);
    this.testDialog()?.nativeElement.showModal();
  }

  async sendTest() {
    const s = this.testing();
    if (!s) return;
    this.busy.set(true);
    try {
      this.testResult.set(
        orThrow(
          await this.api.client.POST("/admin/v1/message-senders/{code}/test", {
            params: { path: { code: s.code } },
            body: { to: this.to().trim() },
          }),
        ),
      );
    } catch {
      this.testResult.set({ ok: false });
    } finally {
      this.busy.set(false);
    }
  }

  async save() {
    let settings: Record<string, unknown>;
    try {
      settings = JSON.parse(this.settings());
    } catch {
      this.problem.set(this.transloco.translate("admin.senders.notJson"));
      return;
    }
    const s = this.editing();
    this.busy.set(true);
    this.problem.set(null);
    try {
      if (!s) {
        orThrow(
          await this.api.client.POST("/admin/v1/message-senders", {
            body: {
              code: this.code().trim(),
              channel: this.channel(),
              adapter: this.channel() === "SMS" ? "HTTP_SMS" : "SMTP",
              displayName: this.displayName().trim(),
              priority: Number(this.priority()),
              secretRef: this.secretRef().trim(),
              settings,
              reason: this.reason().trim(),
            },
          }),
        );
      } else {
        orThrow(
          await this.api.client.PUT("/admin/v1/message-senders/{code}", {
            params: { path: { code: s.code } },
            body: {
              version: s.version,
              displayName: this.displayName().trim(),
              status: this.status(),
              priority: Number(this.priority()),
              secretRef: this.secretRef().trim(),
              settings,
              reason: this.reason().trim(),
            },
          }),
        );
      }
      this.senderDialog()?.nativeElement.close();
      await this.load();
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate("common.checkConnection"),
      );
    } finally {
      this.busy.set(false);
    }
  }

  private open(dialog: ElementRef<HTMLDialogElement> | undefined) {
    this.reason.set("");
    this.problem.set(null);
    dialog?.nativeElement.showModal();
  }
}
