# DataTable

The admin portal's list view: applications, people, bookings, payments, payouts, audit log.

**Consumer provides:** column headers (`th scope="col"`), rows, and which columns are numeric.

- Wrapper: `surface-card`, `line` border, `radius-16`, scrolls sideways inside itself on narrow windows.
- Header: `caption` size, `ink-muted`, weight 600, `line` underline, no wrap.
- Cells: 14px, padding 12/14, `surface-sunken` row dividers. The first column (the name) is weight 600 and links to the record.
- Money and counts: right-aligned, tabular numerals. Amounts in FCFA with a space every three digits: 186 200 FCFA.
- Status: a Pill in its own column.

Don't zebra-stripe rows or put more than one action button in a row; open the record instead.
