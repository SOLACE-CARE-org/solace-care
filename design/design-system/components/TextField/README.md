# TextField

A labelled input. The label sits above the field, always visible; placeholders give an example, never the label.

**Consumer provides:** a stable `id`, the label, optional help text, an error message when invalid, and the size: mobile (`sl-input`, 50px, `radius-14`, 16px text so iOS does not zoom), chat (`sl-input--chat`, 48px pill on `surface-page`) or admin (`sl-input--admin`, 42px, `radius-12`, 14.5px).

- Help text: `meta` in `ink-muted`, under the field.
- Error: 2px `danger` border plus a `meta` message in `danger`, weight 600. Say what went wrong and what to do ("That code isn't right. 2 tries left."). Never colour alone.
- Multi-line notes use the same classes on a `textarea` (min 96px).
- Phone numbers are shown with the +237 prefix and spaced in pairs: +237 6 77 00 00 00.

Don't
- Ask for sex at birth or any field the spec does not list. Eligibility is answered by the module's own optional enrolment question.
- Put the label inside the field as placeholder text.
