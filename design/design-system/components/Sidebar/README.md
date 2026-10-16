# Sidebar

The admin portal's navigation: seven groups, 18 entries, filtered by the signed-in role (see the Admin Portal Spec permissions matrix).

**Consumer provides:** the entries the role may see, each with an 18px stroke icon and label; `aria-current="page"` on the current one; a crisis count when alerts are open.

- 248px wide (`size-admin-sidebar`), `surface-card`, padding 20/14/18.
- Group labels `overline` in `ink-muted`, uppercase in English and French alike.
- Items 38px min, `radius-10`, `label` size: weight 500 `ink-soft`; current item `brand-tint` fill, `brand-deep`, weight 700.
- The crisis count uses CountBadge (`danger-count`).

Don't hide entries by greying them out. If a role can't open a screen, the entry is not shown; a direct link shows the No access state.
