import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { canWrite, hcState, specialtyNames } from './health-check-shared';
import { BackLink } from '../shared/back-link';

type Version = CoreSchemas['HealthCheckVersion'];
type Question = CoreSchemas['HealthCheckQuestion'];
type Rule = CoreSchemas['HealthCheckRule'];
type When = Rule['when'][number];
type TestSet = CoreSchemas['HealthCheckTestSet'];
type TestResult = CoreSchemas['HealthCheckTestResult'];
type Step = 'questions' | 'scoring' | 'review';

const STEPS: Step[] = ['questions', 'scoring', 'review'];
const CHECKS = ['cutoffs', 'wording', 'languages', 'official', 'safety'] as const;
const TOPICS = [
  'anxiety',
  'low-mood',
  'sleep',
  'relationships',
  'grief',
  'womens-health',
  'mens-health',
  'work-stress',
];

/**
 * Admin screens 29 to 31: a health check version's questions, scoring and test and review
 * (HealthCheckQuestions, HealthCheckScoring, HealthCheckReview .dc.html; D55). The safety
 * question and screener items are shown locked; test runs use made-up answer sets only.
 */
@Component({
  selector: 'sl-health-check-version',
  imports: [BackLink, TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/health-check" [label]="'admin.healthCheck.title' | transloco" />
    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (version(); as v) {
      <div class="head">
        <h1 class="sl-title">{{ 'admin.healthCheck.title' | transloco }}</h1>
        <span class="sl-pill sl-pill--warning"
          >v{{ v.number }} · {{ 'admin.healthCheck.status.' + state() | transloco }}</span
        >
      </div>
      <ol class="steps" role="tablist">
        @for (s of steps; track s; let i = $index) {
          <li>
            <button
              type="button"
              role="tab"
              [attr.aria-selected]="s === step()"
              (click)="step.set(s)"
            >
              {{ i + 1 }} {{ 'admin.healthCheck.step.' + s | transloco }}
            </button>
          </li>
        }
      </ol>
      @if (returned(); as r) {
        <p class="sl-card notice" role="status">
          {{ 'admin.healthCheck.returned' | transloco: { who: r.reviewer, comments: r.comments } }}
        </p>
      }
      @if (!editable()) {
        <p class="sl-card notice">{{ 'admin.healthCheck.readOnly' | transloco }}</p>
      }
      @if (failure(); as f) {
        <p class="error" role="alert">{{ f }}</p>
      }

      @switch (step()) {
        @case ('questions') {
          <p class="sl-muted">{{ 'admin.healthCheck.questions.intro' | transloco }}</p>
          <section class="sl-card table-card">
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.healthCheck.questions.col.n' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.questions.col.question' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.questions.col.type' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.questions.col.shown' | transloco }}</th>
                  <th scope="col">
                    <span class="visually-hidden">{{
                      'admin.healthCheck.questions.moveUp' | transloco: { code: '' }
                    }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (q of questions(); track q.code; let i = $index; let last = $last) {
                  <tr [class.selected]="q.code === selectedCode()">
                    <td>{{ i + 1 }}</td>
                    <td>
                      @if (isLocked(q)) {
                        {{ text(q) }}
                      } @else {
                        <button type="button" class="link" (click)="selectedCode.set(q.code)">
                          {{ text(q) }}
                        </button>
                      }
                    </td>
                    <td>
                      @if (isLocked(q)) {
                        <span class="sl-pill">{{
                          'admin.healthCheck.questions.locked' | transloco
                        }}</span>
                        {{
                          (q.kind === 'SAFETY'
                            ? 'admin.healthCheck.questions.safety'
                            : 'admin.healthCheck.questions.screener'
                          ) | transloco
                        }}
                      } @else {
                        {{
                          (q.multi
                            ? 'admin.healthCheck.questions.multi'
                            : 'admin.healthCheck.questions.single'
                          ) | transloco: { n: q.options.length }
                        }}
                      }
                    </td>
                    <td>{{ shown(q) }}</td>
                    <td class="moves">
                      @if (editable()) {
                        <button
                          type="button"
                          class="sl-button sl-button--quiet"
                          [disabled]="i === 0"
                          [attr.aria-label]="
                            'admin.healthCheck.questions.moveUp' | transloco: { code: q.code }
                          "
                          (click)="moveQuestion(i, -1)"
                        >
                          ↑
                        </button>
                        <button
                          type="button"
                          class="sl-button sl-button--quiet"
                          [disabled]="last"
                          [attr.aria-label]="
                            'admin.healthCheck.questions.moveDown' | transloco: { code: q.code }
                          "
                          (click)="moveQuestion(i, 1)"
                        >
                          ↓
                        </button>
                      }
                    </td>
                  </tr>
                }
                <tr>
                  <td>{{ questions().length + 1 }}</td>
                  <td>{{ 'admin.healthCheck.questions.preferences' | transloco }}</td>
                  <td>{{ 'admin.healthCheck.questions.preference' | transloco }}</td>
                  <td>{{ 'admin.healthCheck.questions.neverScored' | transloco }}</td>
                  <td></td>
                </tr>
              </tbody>
            </table>
          </section>
          @if (editable()) {
            <button type="button" class="sl-button add" (click)="addQuestion()">
              {{ 'admin.healthCheck.questions.add' | transloco }}
            </button>
          }
          @if (selected(); as q) {
            <section class="sl-card editor" aria-labelledby="q-title">
              <h2 id="q-title" class="section">
                {{
                  'admin.healthCheck.questions.editing'
                    | transloco: { n: selectedIndex() + 1, code: q.code }
                }}
              </h2>
              <div class="pair">
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.questions.code' | transloco }}</span>
                  <input
                    class="sl-input"
                    [disabled]="!editable()"
                    maxlength="40"
                    [ngModel]="q.code"
                    (ngModelChange)="patchQuestion({ code: $event })"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.questions.stepLabel' | transloco }}</span>
                  <select
                    class="sl-select"
                    [disabled]="!editable()"
                    [ngModel]="q.step"
                    (ngModelChange)="patchQuestion({ step: +$event })"
                  >
                    <option [ngValue]="1">
                      {{ 'admin.healthCheck.questions.steps.1' | transloco }}
                    </option>
                    <option [ngValue]="2">
                      {{ 'admin.healthCheck.questions.steps.2' | transloco }}
                    </option>
                  </select>
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.questions.textEn' | transloco }}</span>
                  <input
                    class="sl-input"
                    lang="en"
                    maxlength="300"
                    [disabled]="!editable()"
                    [ngModel]="q.textEn"
                    (ngModelChange)="patchQuestion({ textEn: $event })"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.questions.textFr' | transloco }}</span>
                  <input
                    class="sl-input"
                    lang="fr"
                    maxlength="300"
                    [disabled]="!editable()"
                    [ngModel]="q.textFr"
                    (ngModelChange)="patchQuestion({ textFr: $event })"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.questions.kind' | transloco }}</span>
                  <select
                    class="sl-select"
                    [disabled]="!editable()"
                    [ngModel]="q.multi"
                    (ngModelChange)="patchQuestion({ multi: $event })"
                  >
                    <option [ngValue]="false">
                      {{ 'admin.healthCheck.questions.kinds.single' | transloco }}
                    </option>
                    <option [ngValue]="true">
                      {{ 'admin.healthCheck.questions.kinds.multi' | transloco }}
                    </option>
                  </select>
                </label>
              </div>
              <h3 class="sub">{{ 'admin.healthCheck.questions.answers' | transloco }}</h3>
              <table class="sl-table">
                <thead>
                  <tr>
                    <th scope="col">{{ 'admin.healthCheck.questions.answerCode' | transloco }}</th>
                    <th scope="col">{{ 'admin.healthCheck.questions.answerEn' | transloco }}</th>
                    <th scope="col">{{ 'admin.healthCheck.questions.answerFr' | transloco }}</th>
                    <th scope="col">{{ 'admin.healthCheck.questions.topic' | transloco }}</th>
                    <th scope="col">
                      <span class="visually-hidden">{{
                        'admin.healthCheck.questions.removeAnswer' | transloco: { n: '' }
                      }}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (o of q.options; track $index; let j = $index) {
                    <tr>
                      <td>
                        <input
                          class="sl-input"
                          maxlength="40"
                          [attr.aria-label]="'admin.healthCheck.questions.answerCode' | transloco"
                          [disabled]="!editable()"
                          [ngModel]="o.code"
                          (ngModelChange)="patchOption(j, { code: $event })"
                        />
                      </td>
                      <td>
                        <input
                          class="sl-input"
                          lang="en"
                          maxlength="120"
                          [attr.aria-label]="'admin.healthCheck.questions.answerEn' | transloco"
                          [disabled]="!editable()"
                          [ngModel]="o.labelEn"
                          (ngModelChange)="patchOption(j, { labelEn: $event })"
                        />
                      </td>
                      <td>
                        <input
                          class="sl-input"
                          lang="fr"
                          maxlength="120"
                          [attr.aria-label]="'admin.healthCheck.questions.answerFr' | transloco"
                          [disabled]="!editable()"
                          [ngModel]="o.labelFr"
                          (ngModelChange)="patchOption(j, { labelFr: $event })"
                        />
                      </td>
                      <td>
                        <select
                          class="sl-select"
                          [attr.aria-label]="'admin.healthCheck.questions.topic' | transloco"
                          [disabled]="!editable()"
                          [ngModel]="o.topic ?? ''"
                          (ngModelChange)="patchOption(j, { topic: $event || undefined })"
                        >
                          <option value="">
                            {{ 'admin.healthCheck.questions.noTopic' | transloco }}
                          </option>
                          @for (t of topics; track t) {
                            <option [value]="t">{{ t }}</option>
                          }
                        </select>
                      </td>
                      <td>
                        @if (editable()) {
                          <button
                            type="button"
                            class="sl-button sl-button--quiet"
                            [attr.aria-label]="
                              'admin.healthCheck.questions.removeAnswer' | transloco: { n: j + 1 }
                            "
                            (click)="removeOption(j)"
                          >
                            ×
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
              @if (editable()) {
                <button type="button" class="sl-button" (click)="addOption()">
                  {{ 'admin.healthCheck.questions.addAnswer' | transloco }}
                </button>
              }
              <fieldset class="row">
                <legend>{{ 'admin.healthCheck.questions.show' | transloco }}</legend>
                <label class="check">
                  <input
                    type="radio"
                    name="show"
                    [disabled]="!editable()"
                    [checked]="!q.showIf"
                    (change)="patchQuestion({ showIf: undefined })"
                  />
                  {{ 'admin.healthCheck.questions.showAlways' | transloco }}
                </label>
                <label class="check">
                  <input
                    type="radio"
                    name="show"
                    [disabled]="!editable()"
                    [checked]="!!q.showIf"
                    (change)="patchQuestion({ showIf: { question: firstOther(q), anyOf: [] } })"
                  />
                  {{ 'admin.healthCheck.questions.showWhen' | transloco }}
                </label>
                @if (q.showIf; as s) {
                  <label class="sl-field">
                    <span>{{ 'admin.healthCheck.questions.showQuestion' | transloco }}</span>
                    <select
                      class="sl-select"
                      [disabled]="!editable()"
                      [ngModel]="s.question"
                      (ngModelChange)="patchQuestion({ showIf: { question: $event, anyOf: [] } })"
                    >
                      @for (other of choiceQuestions(q.code); track other.code) {
                        <option [value]="other.code">{{ other.code }}</option>
                      }
                    </select>
                  </label>
                  <fieldset class="row">
                    <legend>{{ 'admin.healthCheck.questions.showAnswers' | transloco }}</legend>
                    @for (o of optionsOf(s.question); track o.code) {
                      <label class="check">
                        <input
                          type="checkbox"
                          [disabled]="!editable()"
                          [checked]="(s.anyOf ?? []).includes(o.code)"
                          (change)="toggleShowIf(o.code)"
                        />
                        {{ lang() === 'fr' ? o.labelFr : o.labelEn }}
                      </label>
                    }
                  </fieldset>
                }
              </fieldset>
              @if (editable()) {
                <button
                  type="button"
                  class="sl-button sl-button--danger"
                  (click)="removeQuestion()"
                >
                  {{ 'admin.healthCheck.questions.remove' | transloco }}
                </button>
              }
            </section>
          }
          <p class="sl-meta">
            {{
              'admin.healthCheck.questions.numbering'
                | transloco: { minor: nextMinor(), major: nextMajor() }
            }}
          </p>
          <div class="actions">
            @if (editable()) {
              @if (saved()) {
                <span class="sl-meta" role="status">{{
                  'admin.healthCheck.saved' | transloco
                }}</span>
              }
              <button type="button" class="sl-button" [disabled]="busy()" (click)="saveQuestions()">
                {{ 'admin.healthCheck.questions.save' | transloco }}
              </button>
            }
            <button
              type="button"
              class="sl-button sl-button--primary"
              [disabled]="busy()"
              (click)="nextFromQuestions()"
            >
              {{ 'admin.healthCheck.questions.next' | transloco }}
            </button>
          </div>
        }

        @case ('scoring') {
          <p class="sl-muted">{{ 'admin.healthCheck.scoring.intro' | transloco }}</p>
          <section class="sl-card table-card">
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.healthCheck.scoring.col.rule' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.scoring.col.when' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.scoring.col.adds' | transloco }}</th>
                  <th scope="col">{{ 'admin.healthCheck.scoring.col.reason' | transloco }}</th>
                </tr>
              </thead>
              <tbody>
                @for (r of rules(); track $index; let i = $index) {
                  <tr [class.selected]="i === selectedRule()">
                    <td>
                      <button type="button" class="link" (click)="selectedRule.set(i)">
                        {{ r.code }}
                      </button>
                    </td>
                    <td>{{ whenText(r) }}</td>
                    <td>{{ names()[r.specialty] ?? r.specialty }} +{{ r.points }}</td>
                    <td>{{ lang() === 'fr' ? r.reasonFr : r.reasonEn }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
          @if (editable()) {
            <button type="button" class="sl-button add" (click)="addRule()">
              {{ 'admin.healthCheck.scoring.add' | transloco }}
            </button>
          }
          @if (rule(); as r) {
            <section class="sl-card editor" aria-labelledby="r-title">
              <h2 id="r-title" class="section">{{ r.code }}</h2>
              <label class="sl-field">
                <span>{{ 'admin.healthCheck.scoring.code' | transloco }}</span>
                <input
                  class="sl-input"
                  maxlength="60"
                  [disabled]="!editable()"
                  [ngModel]="r.code"
                  (ngModelChange)="patchRule({ code: $event })"
                />
              </label>
              <h3 class="sub">{{ 'admin.healthCheck.scoring.when' | transloco }}</h3>
              @for (w of r.when; track $index; let k = $index) {
                <fieldset class="row condition">
                  <legend class="visually-hidden">{{ k + 1 }}</legend>
                  @if (k > 0) {
                    <span class="sl-meta">{{ 'admin.healthCheck.scoring.and' | transloco }}</span>
                  }
                  <select
                    class="sl-select"
                    [attr.aria-label]="'admin.healthCheck.scoring.when' | transloco"
                    [disabled]="!editable()"
                    [ngModel]="w.screener ? 'screener' : 'answer'"
                    (ngModelChange)="
                      patchWhen(
                        k,
                        $event === 'screener'
                          ? { screener: 'GAD7', atLeast: 10 }
                          : { question: firstChoice(), anyOf: [] },
                        true
                      )
                    "
                  >
                    <option value="answer">
                      {{ 'admin.healthCheck.scoring.conditionKind.answer' | transloco }}
                    </option>
                    <option value="screener">
                      {{ 'admin.healthCheck.scoring.conditionKind.screener' | transloco }}
                    </option>
                  </select>
                  @if (w.screener) {
                    <label class="sl-field">
                      <span>{{ 'admin.healthCheck.scoring.screener' | transloco }}</span>
                      <select
                        class="sl-select"
                        [disabled]="!editable()"
                        [ngModel]="w.screener"
                        (ngModelChange)="patchWhen(k, { screener: $event })"
                      >
                        <option value="PHQ9">PHQ-9</option>
                        <option value="GAD7">GAD-7</option>
                      </select>
                    </label>
                    <label class="sl-field small">
                      <span>{{ 'admin.healthCheck.scoring.from' | transloco }}</span>
                      <input
                        class="sl-input"
                        type="number"
                        min="0"
                        max="27"
                        [disabled]="!editable()"
                        [ngModel]="w.atLeast"
                        (ngModelChange)="patchWhen(k, { atLeast: num($event) })"
                      />
                    </label>
                    <label class="sl-field small">
                      <span>{{ 'admin.healthCheck.scoring.to' | transloco }}</span>
                      <input
                        class="sl-input"
                        type="number"
                        min="0"
                        max="27"
                        [disabled]="!editable()"
                        [ngModel]="w.atMost"
                        (ngModelChange)="patchWhen(k, { atMost: num($event) })"
                      />
                    </label>
                  } @else {
                    <label class="sl-field">
                      <span>{{ 'admin.healthCheck.scoring.question' | transloco }}</span>
                      <select
                        class="sl-select"
                        [disabled]="!editable()"
                        [ngModel]="w.question"
                        (ngModelChange)="patchWhen(k, { question: $event, anyOf: [] })"
                      >
                        @for (q of choiceQuestions(''); track q.code) {
                          <option [value]="q.code">{{ q.code }}</option>
                        }
                      </select>
                    </label>
                    <fieldset class="row">
                      <legend>{{ 'admin.healthCheck.scoring.answers' | transloco }}</legend>
                      @for (o of optionsOf(w.question); track o.code) {
                        <label class="check">
                          <input
                            type="checkbox"
                            [disabled]="!editable()"
                            [checked]="(w.anyOf ?? []).includes(o.code)"
                            (change)="toggleWhen(k, o.code)"
                          />
                          {{ lang() === 'fr' ? o.labelFr : o.labelEn }}
                        </label>
                      }
                    </fieldset>
                  }
                  @if (editable() && r.when.length > 1) {
                    <button
                      type="button"
                      class="sl-button sl-button--quiet"
                      (click)="removeWhen(k)"
                    >
                      {{ 'admin.healthCheck.scoring.removeCondition' | transloco: { n: k + 1 } }}
                    </button>
                  }
                </fieldset>
              }
              @if (editable() && r.when.length < 3) {
                <button type="button" class="sl-button" (click)="addWhen()">
                  {{ 'admin.healthCheck.scoring.addCondition' | transloco }}
                </button>
              }
              <div class="row">
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.scoring.addTo' | transloco }}</span>
                  <select
                    class="sl-select"
                    [disabled]="!editable()"
                    [ngModel]="r.specialty"
                    (ngModelChange)="patchRule({ specialty: $event })"
                  >
                    @for (code of specialtyCodes(); track code) {
                      <option [value]="code">{{ names()[code] ?? code }}</option>
                    }
                  </select>
                </label>
                <label class="sl-field small">
                  <span>{{ 'admin.healthCheck.scoring.points' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="1"
                    max="10"
                    [disabled]="!editable()"
                    [ngModel]="r.points"
                    (ngModelChange)="patchRule({ points: num($event) ?? 1 })"
                  />
                </label>
              </div>
              <label class="sl-field">
                <span>{{ 'admin.healthCheck.scoring.reasonEn' | transloco }}</span>
                <textarea
                  class="sl-textarea"
                  rows="2"
                  lang="en"
                  maxlength="200"
                  [disabled]="!editable()"
                  [ngModel]="r.reasonEn"
                  (ngModelChange)="patchRule({ reasonEn: $event })"
                ></textarea>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.healthCheck.scoring.reasonFr' | transloco }}</span>
                <textarea
                  class="sl-textarea"
                  rows="2"
                  lang="fr"
                  maxlength="200"
                  [disabled]="!editable()"
                  [ngModel]="r.reasonFr"
                  (ngModelChange)="patchRule({ reasonFr: $event })"
                ></textarea>
              </label>
              <p class="sl-meta">{{ 'admin.healthCheck.scoring.reasonsNote' | transloco }}</p>
              @if (editable()) {
                <button type="button" class="sl-button sl-button--danger" (click)="removeRule()">
                  {{ 'admin.healthCheck.scoring.remove' | transloco }}
                </button>
              }
            </section>
          }
          <section class="sl-card locked">
            <h2 class="section">{{ 'admin.healthCheck.scoring.safetyTitle' | transloco }}</h2>
            <p>{{ 'admin.healthCheck.scoring.safetyBody' | transloco }}</p>
          </section>
          <section class="sl-card" aria-labelledby="order-title">
            <h2 id="order-title" class="section">
              {{ 'admin.healthCheck.scoring.order' | transloco }}
            </h2>
            <ol class="order">
              @for (code of order(); track code; let i = $index; let last = $last) {
                <li>
                  <span>{{ i + 1 }} · {{ names()[code] ?? code }}</span>
                  @if (editable()) {
                    <button
                      type="button"
                      class="sl-button sl-button--quiet"
                      [disabled]="i === 0"
                      [attr.aria-label]="
                        'admin.healthCheck.scoring.moveUp'
                          | transloco: { name: names()[code] ?? code }
                      "
                      (click)="moveOrder(i, -1)"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      class="sl-button sl-button--quiet"
                      [disabled]="last"
                      [attr.aria-label]="
                        'admin.healthCheck.scoring.moveDown'
                          | transloco: { name: names()[code] ?? code }
                      "
                      (click)="moveOrder(i, 1)"
                    >
                      ↓
                    </button>
                  }
                </li>
              }
            </ol>
            <p class="sl-meta">{{ 'admin.healthCheck.scoring.orderNote' | transloco: { n: 1 } }}</p>
          </section>
          <div class="actions">
            <button type="button" class="sl-button" (click)="step.set('questions')">
              {{ 'admin.healthCheck.scoring.back' | transloco }}
            </button>
            @if (editable()) {
              @if (saved()) {
                <span class="sl-meta" role="status">{{
                  'admin.healthCheck.saved' | transloco
                }}</span>
              }
              <button type="button" class="sl-button" [disabled]="busy()" (click)="saveScoring()">
                {{ 'admin.healthCheck.scoring.save' | transloco }}
              </button>
            }
            <button
              type="button"
              class="sl-button sl-button--primary"
              [disabled]="busy()"
              (click)="nextFromScoring()"
            >
              {{ 'admin.healthCheck.scoring.next' | transloco }}
            </button>
          </div>
        }

        @case ('review') {
          <p class="sl-meta">
            @if (mayReview()) {
              {{ 'admin.healthCheck.review.written' | transloco: { who: v.draftedBy, me: me() } }}
            } @else {
              {{ 'admin.healthCheck.review.writtenOnly' | transloco: { who: v.draftedBy } }}
            }
          </p>
          @if (v.approvedBy && v.status === 'IN_REVIEW') {
            <div class="sl-card approved">
              <p>{{ 'admin.healthCheck.review.approved' | transloco: { who: v.approvedBy } }}</p>
              @if (mayWrite()) {
                <button
                  type="button"
                  class="sl-button sl-button--primary"
                  [disabled]="busy()"
                  (click)="publish()"
                >
                  {{ 'admin.healthCheck.review.publish' | transloco: { n: v.number } }}
                </button>
              }
            </div>
          }
          @if (v.status === 'DRAFT' && v.problems.length) {
            <section class="sl-card">
              <h2 class="section">{{ 'admin.healthCheck.review.problems' | transloco }}</h2>
              <ul>
                @for (p of v.problems; track p) {
                  <li>{{ problemText(p) }}</li>
                }
              </ul>
            </section>
          }
          <div class="layout">
            <div class="main">
              <section class="sl-card table-card" aria-labelledby="changes-title">
                <h2 id="changes-title" class="section pad">
                  @if (published(); as p) {
                    {{ 'admin.healthCheck.review.changed' | transloco: { n: p.number } }}
                  } @else {
                    {{ 'admin.healthCheck.review.changedFirst' | transloco }}
                  }
                </h2>
                <table class="sl-table">
                  <thead>
                    <tr>
                      <th scope="col">{{ 'admin.healthCheck.review.part' | transloco }}</th>
                      @if (published(); as p) {
                        <th scope="col">
                          {{ 'admin.healthCheck.review.before' | transloco: { n: p.number } }}
                        </th>
                      }
                      <th scope="col">
                        {{ 'admin.healthCheck.review.after' | transloco: { n: v.number } }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (c of changes(); track c.part) {
                      <tr>
                        <th scope="row">{{ c.part }}</th>
                        @if (published()) {
                          <td>{{ c.before }}</td>
                        }
                        <td>{{ c.after }}</td>
                      </tr>
                    } @empty {
                      <tr>
                        <td [attr.colspan]="published() ? 3 : 2">
                          {{ 'admin.healthCheck.review.noChange' | transloco }}
                        </td>
                      </tr>
                    }
                    <tr>
                      <th scope="row">{{ 'admin.healthCheck.review.safetyRow' | transloco }}</th>
                      @if (published()) {
                        <td>{{ 'admin.healthCheck.review.locked' | transloco }}</td>
                      }
                      <td>{{ 'admin.healthCheck.review.lockedSame' | transloco }}</td>
                    </tr>
                  </tbody>
                </table>
              </section>

              <section class="sl-card table-card" aria-labelledby="sets-title" aria-live="polite">
                <h2 id="sets-title" class="section pad">
                  {{ 'admin.healthCheck.review.sets' | transloco }}
                </h2>
                <p class="sl-meta pad">{{ 'admin.healthCheck.review.setsNote' | transloco }}</p>
                <table class="sl-table">
                  <thead>
                    <tr>
                      <th scope="col">{{ 'admin.healthCheck.review.col.set' | transloco }}</th>
                      <th scope="col">
                        @if (published(); as p) {
                          {{
                            'admin.healthCheck.review.col.published' | transloco: { n: p.number }
                          }}
                        } @else {
                          {{ 'admin.healthCheck.review.publishedNone' | transloco }}
                        }
                      </th>
                      <th scope="col">
                        {{ 'admin.healthCheck.review.col.draft' | transloco: { n: v.number } }}
                      </th>
                      <th scope="col">{{ 'admin.healthCheck.review.col.expected' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (t of results(); track t.name) {
                      <tr>
                        <td>{{ t.name }}</td>
                        <td>{{ outcome(t.publishedSafetyStop, t.published) }}</td>
                        <td>
                          {{ outcome(t.safetyStop, t.specialty) }}
                          @if (changedResult(t)) {
                            <span class="sl-pill sl-pill--warning">{{
                              'admin.healthCheck.review.changedPill' | transloco
                            }}</span>
                          }
                          @if (t.expected && t.expected !== t.specialty) {
                            <span class="sl-pill sl-pill--danger">{{
                              'admin.healthCheck.review.unexpected' | transloco
                            }}</span>
                          }
                        </td>
                        <td>{{ t.expected ? (names()[t.expected] ?? t.expected) : '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
                <p class="pad sl-meta">
                  {{
                    'admin.healthCheck.review.summary'
                      | transloco: { changed: changedCount(), total: results().length }
                  }}
                </p>
                <div class="pad actions start">
                  <button type="button" class="sl-button" [disabled]="busy()" (click)="runTests()">
                    {{ 'admin.healthCheck.review.run' | transloco }}
                  </button>
                </div>
              </section>
            </div>

            <aside class="sl-card" aria-labelledby="check-title">
              <h2 id="check-title" class="section">
                {{ 'admin.healthCheck.review.checklist' | transloco }}
              </h2>
              @for (c of checks; track c) {
                <label class="check block">
                  <input
                    type="checkbox"
                    [disabled]="!v.canApprove"
                    [checked]="ticked().has(c)"
                    (change)="tick(c)"
                  />
                  {{ 'admin.healthCheck.review.check.' + c | transloco }}
                </label>
              }
              @if (v.canApprove) {
                <label class="sl-field">
                  <span>{{ 'admin.healthCheck.review.comments' | transloco }}</span>
                  <textarea
                    class="sl-textarea"
                    rows="4"
                    maxlength="4000"
                    [(ngModel)]="comments"
                  ></textarea>
                </label>
              }
              <p class="sl-meta">{{ 'admin.healthCheck.review.ownDraft' | transloco }}</p>
              <div class="actions">
                @if (v.status === 'DRAFT' && mayWrite()) {
                  <button
                    type="button"
                    class="sl-button sl-button--primary"
                    [disabled]="busy() || v.problems.length > 0"
                    (click)="submit()"
                  >
                    {{ 'admin.healthCheck.review.submit' | transloco }}
                  </button>
                }
                @if (v.canApprove) {
                  <button
                    type="button"
                    class="sl-button"
                    [disabled]="busy() || !comments().trim()"
                    (click)="decide(false)"
                  >
                    {{ 'admin.healthCheck.review.return' | transloco }}
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--primary"
                    [disabled]="busy() || ticked().size < checks.length"
                    (click)="decide(true)"
                  >
                    {{ 'admin.healthCheck.review.approve' | transloco: { n: v.number } }}
                  </button>
                }
              </div>
            </aside>
          </div>
        }
      }
    } @else {
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    .head {
      display: flex;
      align-items: center;
      gap: var(--space-12);
    }
    .steps {
      list-style: none;
      display: flex;
      gap: var(--space-8);
      padding: 0;
      margin: var(--space-16) 0 var(--space-20);
    }
    .steps button {
      min-height: 44px;
      padding: 0 var(--space-16);
      border: 1px solid var(--color-line, #dde3dc);
      border-radius: 999px;
      background: transparent;
      font: inherit;
      cursor: pointer;
    }
    .steps button[aria-selected='true'] {
      background: var(--brand);
      border-color: var(--brand);
      color: var(--white, #fff);
      font-weight: 600;
    }
    .notice {
      margin: 0 0 var(--space-16);
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .pad {
      padding-left: var(--space-20);
      padding-right: var(--space-20);
    }
    h2.pad {
      padding-top: var(--space-16);
    }
    .section {
      font-size: 1rem;
      margin: 0 0 var(--space-8);
    }
    .sub {
      font-size: 0.95rem;
      margin: var(--space-16) 0 var(--space-8);
    }
    .editor {
      margin-top: var(--space-16);
      display: grid;
      gap: var(--space-12);
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-12);
    }
    .row {
      border: 0;
      padding: 0;
      margin: 0;
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-12);
    }
    .condition {
      padding-bottom: var(--space-8);
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .small {
      max-width: 8rem;
    }
    .check {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .check.block {
      display: flex;
    }
    .link {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      color: var(--brand);
      cursor: pointer;
      text-align: left;
      min-height: 44px;
    }
    tr.selected {
      background: var(--brand-tint, #e6f1ee);
    }
    .moves {
      white-space: nowrap;
    }
    .moves button {
      min-width: 44px;
    }
    .add {
      margin-top: var(--space-12);
    }
    .locked {
      margin-top: var(--space-16);
    }
    .order {
      list-style: none;
      padding: 0;
      margin: 0 0 var(--space-8);
    }
    .order li {
      display: flex;
      align-items: center;
      gap: var(--space-8);
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 22rem;
      gap: var(--space-20);
      align-items: start;
      margin-top: var(--space-16);
    }
    .main {
      display: grid;
      gap: var(--space-16);
    }
    .approved {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-12);
    }
    .actions {
      display: flex;
      justify-content: end;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-8);
      margin-top: var(--space-16);
    }
    .actions.start {
      justify-content: start;
      margin-bottom: var(--space-16);
    }
    .error {
      color: var(--danger);
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class HealthCheckVersionPage {
  private readonly api = inject(CoreApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly id = input.required<string>();
  readonly steps = STEPS;
  readonly checks = CHECKS;
  readonly topics = TOPICS;
  readonly step = signal<Step>('questions');
  readonly version = signal<Version | null>(null);
  readonly published = signal<Version | null>(null);
  readonly questions = signal<Question[]>([]);
  readonly rules = signal<Rule[]>([]);
  readonly order = signal<string[]>([]);
  readonly results = signal<TestResult[]>([]);
  readonly names = signal<Record<string, string>>({});
  readonly selectedCode = signal<string | null>(null);
  readonly selectedRule = signal<number | null>(null);
  readonly ticked = signal(new Set<string>());
  readonly comments = signal('');
  readonly error = signal(false);
  readonly failure = signal<string | null>(null);
  readonly saved = signal(false);
  readonly busy = signal(false);
  readonly lang = signal(this.transloco.getActiveLang());

  readonly me = computed(() => this.staff.name());
  readonly mayWrite = computed(() => canWrite(this.staff.roles()));
  readonly mayReview = computed(() => this.staff.roles().includes('CLINICAL_REVIEWER'));
  readonly editable = computed(() => this.mayWrite() && this.version()?.status === 'DRAFT');
  readonly state = computed(() => hcState(this.version()?.status, this.version()?.approvedBy));
  readonly selected = computed(
    () => this.questions().find((q) => q.code === this.selectedCode()) ?? null,
  );
  readonly selectedIndex = computed(() =>
    this.questions().findIndex((q) => q.code === this.selectedCode()),
  );
  readonly rule = computed(() => {
    const i = this.selectedRule();
    return i === null ? null : (this.rules()[i] ?? null);
  });
  readonly specialtyCodes = computed(() => {
    const known = Object.keys(this.names());
    const used = [...this.order(), ...this.rules().map((r) => r.specialty)];
    return [...new Set([...this.order(), ...known, ...used])];
  });
  readonly returned = computed(() => {
    const v = this.version();
    const last = v?.reviews.at(-1);
    return v?.status === 'DRAFT' && last?.decision === 'RETURNED' ? last : null;
  });
  readonly nextMinor = computed(() => {
    const p = this.published();
    return p ? `${p.number.split('.')[0]}.${Number(p.number.split('.')[1] ?? 0) + 1}` : '1.1';
  });
  readonly nextMajor = computed(() => {
    const p = this.published();
    return p ? `${Number(p.number.split('.')[0]) + 1}` : '2';
  });
  readonly changes = computed(() =>
    diff(this.published(), this.version(), this.lang(), this.transloco),
  );

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.id();
      untracked(() => void this.load());
    });
  }

  async load() {
    this.error.set(false);
    try {
      const v = orThrow(
        await this.api.client.GET('/admin/v1/health-check/versions/{versionId}', {
          params: { path: { versionId: this.id() } },
        }),
      );
      this.adopt(v);
      const rows = orThrow(await this.api.client.GET('/admin/v1/health-check/versions'));
      const live = rows.find((r) => r.status === 'PUBLISHED' && r.id !== v.id);
      this.published.set(
        live
          ? orThrow(
              await this.api.client.GET('/admin/v1/health-check/versions/{versionId}', {
                params: { path: { versionId: live.id } },
              }),
            )
          : null,
      );
      this.names.set(await specialtyNames(this.api, this.lang()));
      void this.runTests();
    } catch {
      this.error.set(true);
    }
  }

  private adopt(v: Version) {
    this.version.set(v);
    this.questions.set(structuredClone(v.questions));
    this.rules.set(structuredClone(v.rules));
    this.order.set([...v.specialtyOrder]);
  }

  // --- Questions --------------------------------------------------------------------------------

  isLocked(q: Question): boolean {
    return q.kind === 'SAFETY' || q.kind === 'SCREENER';
  }

  text(q: Question): string {
    return (this.lang() === 'fr' ? q.textFr : q.textEn) || q.code;
  }

  shown(q: Question): string {
    const s = q.showIf;
    if (!s) return this.transloco.translate('admin.healthCheck.questions.always');
    if (s.screener) {
      return this.transloco.translate('admin.healthCheck.questions.whenScreener', {
        screener: s.screener === 'PHQ9' ? 'PHQ-9' : 'GAD-7',
        items: s.firstItems ?? 2,
        n: s.atLeast ?? 0,
      });
    }
    return this.transloco.translate('admin.healthCheck.questions.whenAnswer', {
      question: s.question,
      answers: (s.anyOf ?? []).join(', '),
    });
  }

  choiceQuestions(except: string): Question[] {
    return this.questions().filter((q) => q.kind === 'CHOICE' && q.code !== except);
  }

  firstOther(q: Question): string | undefined {
    return this.choiceQuestions(q.code)[0]?.code;
  }

  firstChoice(): string | undefined {
    return this.choiceQuestions('')[0]?.code;
  }

  optionsOf(code: string | undefined): Question['options'] {
    return this.questions().find((q) => q.code === code)?.options ?? [];
  }

  addQuestion() {
    const n = this.questions().length + 1;
    const q: Question = {
      code: `question_${n}`,
      kind: 'CHOICE',
      multi: false,
      step: 1,
      textEn: '',
      textFr: '',
      options: [
        { code: 'yes', labelEn: '', labelFr: '' },
        { code: 'no', labelEn: '', labelFr: '' },
      ],
    };
    this.questions.update((list) => [...list, q]);
    this.selectedCode.set(q.code);
  }

  patchQuestion(change: Partial<Question>) {
    const code = this.selectedCode();
    this.saved.set(false);
    this.questions.update((list) => list.map((q) => (q.code === code ? { ...q, ...change } : q)));
    if (change.code) this.selectedCode.set(change.code);
  }

  patchOption(i: number, change: Partial<Question['options'][number]>) {
    const q = this.selected();
    if (q)
      this.patchQuestion({ options: q.options.map((o, j) => (j === i ? { ...o, ...change } : o)) });
  }

  addOption() {
    const q = this.selected();
    if (q)
      this.patchQuestion({
        options: [
          ...q.options,
          { code: `answer_${q.options.length + 1}`, labelEn: '', labelFr: '' },
        ],
      });
  }

  removeOption(i: number) {
    const q = this.selected();
    if (q) this.patchQuestion({ options: q.options.filter((_, j) => j !== i) });
  }

  toggleShowIf(option: string) {
    const s = this.selected()?.showIf;
    if (!s) return;
    const list = s.anyOf ?? [];
    this.patchQuestion({
      showIf: {
        ...s,
        anyOf: list.includes(option) ? list.filter((x) => x !== option) : [...list, option],
      },
    });
  }

  removeQuestion() {
    const code = this.selectedCode();
    this.questions.update((list) => list.filter((q) => q.code !== code));
    this.selectedCode.set(null);
  }

  moveQuestion(i: number, by: -1 | 1) {
    this.questions.update((list) => move(list, i, by));
  }

  async saveQuestions(): Promise<boolean> {
    return this.save(async () =>
      orThrow(
        await this.api.client.PUT('/admin/v1/health-check/versions/{versionId}/questions', {
          params: { path: { versionId: this.id() } },
          body: this.questions(),
        }),
      ),
    );
  }

  async nextFromQuestions() {
    if (!this.editable() || (await this.saveQuestions())) this.step.set('scoring');
  }

  // --- Scoring ----------------------------------------------------------------------------------

  whenText(r: Rule): string {
    return r.when
      .map((w) => {
        if (w.screener) {
          const screener = w.screener === 'PHQ9' ? 'PHQ-9' : 'GAD-7';
          const key =
            w.atLeast != null && w.atMost != null
              ? 'whenRange'
              : w.atMost != null
                ? 'whenAtMost'
                : 'whenAtLeast';
          return this.transloco.translate(`admin.healthCheck.scoring.${key}`, {
            screener,
            from: w.atLeast,
            to: w.atMost,
          });
        }
        const labels = this.optionsOf(w.question)
          .filter((o) => (w.anyOf ?? []).includes(o.code))
          .map((o) => (this.lang() === 'fr' ? o.labelFr : o.labelEn));
        return this.transloco.translate('admin.healthCheck.scoring.whenAnswer', {
          question: w.question,
          answers: labels.join(', '),
        });
      })
      .join(` ${this.transloco.translate('admin.healthCheck.scoring.and')} `);
  }

  addRule() {
    const r: Rule = {
      code: `rule_${this.rules().length + 1}`,
      when: [{ question: this.firstChoice(), anyOf: [] }],
      specialty: this.order()[0] ?? 'counselling',
      points: 1,
      reasonEn: '',
      reasonFr: '',
    };
    this.rules.update((list) => [...list, r]);
    this.selectedRule.set(this.rules().length - 1);
  }

  patchRule(change: Partial<Rule>) {
    const i = this.selectedRule();
    this.saved.set(false);
    this.rules.update((list) => list.map((r, j) => (j === i ? { ...r, ...change } : r)));
  }

  patchWhen(k: number, change: Partial<When>, replace = false) {
    const r = this.rule();
    if (r)
      this.patchRule({
        when: r.when.map((w, j) => (j === k ? (replace ? change : { ...w, ...change }) : w)),
      });
  }

  toggleWhen(k: number, option: string) {
    const w = this.rule()?.when[k];
    const list = w?.anyOf ?? [];
    this.patchWhen(k, {
      anyOf: list.includes(option) ? list.filter((x) => x !== option) : [...list, option],
    });
  }

  addWhen() {
    const r = this.rule();
    if (r) this.patchRule({ when: [...r.when, { question: this.firstChoice(), anyOf: [] }] });
  }

  removeWhen(k: number) {
    const r = this.rule();
    if (r) this.patchRule({ when: r.when.filter((_, j) => j !== k) });
  }

  removeRule() {
    const i = this.selectedRule();
    this.rules.update((list) => list.filter((_, j) => j !== i));
    this.selectedRule.set(null);
  }

  moveOrder(i: number, by: -1 | 1) {
    this.order.update((list) => move(list, i, by));
  }

  num(value: unknown): number | undefined {
    if (value === '' || value === null || value === undefined) return undefined;
    const n = Number(value);
    return Number.isFinite(n) ? n : undefined;
  }

  async saveScoring(): Promise<boolean> {
    const order = [...new Set([...this.order(), ...this.rules().map((r) => r.specialty)])];
    return this.save(async () =>
      orThrow(
        await this.api.client.PUT('/admin/v1/health-check/versions/{versionId}/scoring', {
          params: { path: { versionId: this.id() } },
          body: { rules: this.rules(), specialtyOrder: order },
        }),
      ),
    );
  }

  async nextFromScoring() {
    if (!this.editable() || (await this.saveScoring())) {
      this.step.set('review');
      void this.runTests();
    }
  }

  // --- Test and review --------------------------------------------------------------------------

  outcome(stop: boolean | undefined, specialty: string | undefined): string {
    if (stop) return this.transloco.translate('admin.healthCheck.review.urgent');
    if (!specialty) return '—';
    return this.names()[specialty] ?? specialty;
  }

  changedResult(t: TestResult): boolean {
    if (!this.published()) return false;
    return (
      t.safetyStop !== Boolean(t.publishedSafetyStop) ||
      (!t.safetyStop && t.specialty !== t.published)
    );
  }

  changedCount(): number {
    return this.results().filter((t) => this.changedResult(t)).length;
  }

  problemText(p: string): string {
    const [kind, code] = p.split(':');
    return this.transloco.translate(`admin.healthCheck.review.problem.${kind}`, {
      code: code ?? '',
    });
  }

  tick(check: string) {
    this.ticked.update((set) => {
      const next = new Set(set);
      if (next.has(check)) next.delete(check);
      else next.add(check);
      return next;
    });
  }

  async runTests() {
    try {
      this.results.set(
        orThrow(
          await this.api.client.POST('/admin/v1/health-check/versions/{versionId}/test-run', {
            params: { path: { versionId: this.id() } },
          }),
        ),
      );
    } catch {
      this.results.set([]);
    }
  }

  async submit() {
    await this.save(async () =>
      orThrow(
        await this.api.client.POST('/admin/v1/health-check/versions/{versionId}/submission', {
          params: { path: { versionId: this.id() } },
        }),
      ),
    );
  }

  async decide(approve: boolean) {
    await this.save(async () =>
      orThrow(
        await this.api.client.POST('/admin/v1/health-check/versions/{versionId}/review', {
          params: { path: { versionId: this.id() } },
          body: {
            approve,
            ...(this.comments().trim() ? { comments: this.comments().trim() } : {}),
          },
        }),
      ),
    );
  }

  async publish() {
    await this.save(async () =>
      orThrow(
        await this.api.client.POST('/admin/v1/health-check/versions/{versionId}/publication', {
          params: { path: { versionId: this.id() } },
        }),
      ),
    );
  }

  private async save(call: () => Promise<Version>): Promise<boolean> {
    this.busy.set(true);
    this.failure.set(null);
    try {
      this.adopt(await call());
      this.saved.set(true);
      return true;
    } catch (e) {
      this.failure.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}

function move<T>(list: T[], i: number, by: -1 | 1): T[] {
  const copy = [...list];
  const [item] = copy.splice(i, 1);
  copy.splice(i + by, 0, item!);
  return copy;
}

interface Change {
  part: string;
  before: string;
  after: string;
}

/** What differs from the published version: question wording and answers, rules and points. */
function diff(
  before: Version | null,
  after: Version | null,
  lang: string,
  t: TranslocoService,
): Change[] {
  if (!after) return [];
  const none = t.translate('admin.healthCheck.review.none');
  const rows: Change[] = [];
  const text = (q: Question | undefined) => (q ? (lang === 'fr' ? q.textFr : q.textEn) : '');
  const answers = (q: Question | undefined) =>
    q ? q.options.map((o) => (lang === 'fr' ? o.labelFr : o.labelEn)).join(', ') : '';
  const old = new Map((before?.questions ?? []).map((q) => [q.code, q]));
  for (const q of after.questions) {
    if (q.kind !== 'CHOICE') continue;
    const p = old.get(q.code);
    if (!before || text(p) !== text(q))
      rows.push({ part: q.code, before: text(p) || none, after: text(q) || none });
    if (before && answers(p) !== answers(q))
      rows.push({ part: `${q.code} ·`, before: answers(p) || none, after: answers(q) || none });
  }
  const oldRules = new Map((before?.rules ?? []).map((r) => [r.code, r]));
  for (const r of after.rules) {
    const p = oldRules.get(r.code);
    const now = `${r.specialty} +${r.points}`;
    const then = p ? `${p.specialty} +${p.points}` : '';
    if (!before || now !== then || JSON.stringify(p?.when) !== JSON.stringify(r.when)) {
      rows.push({ part: r.code, before: then || none, after: now });
    }
  }
  return rows;
}
