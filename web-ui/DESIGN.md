# Apache Camel Gateway UI - design brief

Scope: the module's Gateway pages (`web-ui/src`). The host is the Ignition 8.3 Gateway web app; its
`@inductiveautomation/ignition-web-ui` components (PageHeader, DataGrid, Drawer, Button, form inputs)
and `@inductiveautomation/ignition-gateway-lib` workflows (ResourceModeDataGrid, create/edit wizard)
own the chrome. Module-owned markup is styled only through `src/camelRoutes/camel.css`.

## 1. Tokens (extracted from `camel.css`; every value traces here)

Colour roles map onto host CSS variables with a fallback, so light/dark host themes carry through:

| Role | Token | Host variable |
|---|---|---|
| Ink (body text) | `--camel-ink` | `--neutral-90` |
| Muted text, hints | `--camel-muted` | `--neutral-60` |
| Hairlines | `--camel-border` | `--neutral-20` |
| Soft surface (tags, chips, notices) | `--camel-soft` | `--neutral-10` |
| Card surface | `--camel-surface` | `--white` |
| Accent (links, selected tab, focus) | `--camel-accent` | `--primary` |
| Accent wash (selected row) | `--primary-10` | host |
| Success / warning / error | `--camel-success` / `--camel-warning` / `--camel-error` | `--success` / fixed / `--error` |

Type: host font stack (inherited). Scale used by module markup: 25px metric value, 15px section h3,
14px page intro, 13px body/controls/hints in dense tables, 12px hint/caption, 11px tag.
Spacing: 4px grid. Used steps: 4, 6, 8, 10, 12, 14, 16, 20, 24. Section cards: 16px padding, 16px stack
gap, 8px radius. Controls: 36px tall inputs/selects, 4px radius; 6px radius for choice cards and
notices; 2px accent underline marks the selected tab.
Focus: 2px accent outline, 3px offset (inset -2px inside tab strips and list rows).
Motion: only the disclosure chevron rotates (120ms); none under `prefers-reduced-motion`.
Container queries (`camel-layout`) reflow forms at 640/380/300px because editors also live in drawers.

## 2. Primitives (`src/camelRoutes/CamelUi.jsx`, `configurationFields.jsx`)

FormSection (card with h3 + trailing slot), Disclosure (details/summary), ChoiceGroup (radio cards),
Tabs (ARIA tablist, roving tabindex, arrow/Home/End keys, invalid marker, count badge), Notice (status
or alert), Tag (+ success/warning/error tones), RemoveButton, Field/Toggle/ResourceChoice/SecretPicker/
SelectionList/StringMap for react-hook-form bound inputs. New in this change: ResourceGroup (a
SelectionList scoped to one Library category, with its create/open actions) and LibraryLink.

## 3. Screens

### Integrations (`CamelIntegrationsPage`, mounted at `/services/apache-camel/integrations`)

Primary action: Create Integration (native grid action bar button).
Priorities: 1 the integrations grid with runtime state, 2 a one-line definition of what an
integration is (one Camel context, one or more routes), 3 View Details per row.
Left out on purpose: stat tiles over the grid, a second intro sentence, a graph/preview of routes.
Required content: NAME, LANGUAGE, ENABLED, RUNTIME columns; runtime-unavailable notice; View Details;
details drawer with Overview (status, restart, metrics, HTTP access, routes table and per-route
steps), Diagnostics, and Resources (required resources, used-by / change impact) tabs.

Integration editor (create/edit wizard body, host-owned modal):
- Header card: Name (create only), Description.
- Section tabs: Definition | Resources | Settings. Definition holds the language selector and YAML or
  Jython project/function fields plus the context-ownership explanation. Resources holds six groups
  bound to the explicit `components`, `registry`, `kamelets` arrays: Connections,
  Scheduling and Advanced component defaults (components by metadata), Certificates,
  Advanced objects (registry by type), Templates (kamelets). Each
  group has a filterable checklist, a Create action (nested native workflow, draft kept) and an Open in
  Library link. Supporting objects referenced by a selected connection are listed as included in
  their category. A connection editor can create its certificate without closing either draft. Settings
  holds Properties, Secret references and HTTP access (allowed security levels).
- Validation errors re-open the tab that owns the failing field after submit.
Layout: editor - sections must not stack into one long scroll inside a modal -> tabbed sections,
single column, groups as cards (stack primitive); scroll owner: the host wizard body; anti-patterns:
nested scroll areas taller than the modal, two-column grids under 640px.

### Library (`CamelLibraryPage`, mounted at `/services/apache-camel/library`)

Primary action: the active category's native Create button.
Priorities: 1 the category tab strip (Connections, Certificates, Scheduling, Advanced objects,
Templates, Advanced component defaults), 2 the native resource grid for the active category,
3 a one-line category description.
Left out on purpose: counts on tabs (would duplicate grid fetches), per-category stat tiles, a
cross-category search.
Required content: tab strip with `?tab=` deep links (connections|certificates|scheduling|objects|
templates|component-defaults; unknown or missing -> connections), native ResourceModeDataGrid instances with the
category's columns, create/edit forms reused from the existing Components/Registry/Kamelets pages,
the component catalogue browser under Connections, View Details (resources) per row.
Layout: library - resource types compete for one page -> category tabs above the relevant native grid
(scroll-body-shell owned by the host page); scroll owner: host Content; anti-patterns: stacking all
six grids, hiding the tab strip in a dropdown on desktop.

Component forms, lists and integration selectors share the installed Camel metadata classifier:
the `scheduling` label takes precedence, then `remote: true` identifies Connections. Everything
else, including missing metadata, belongs to Advanced component defaults. Camel's remote flag
also includes files and Kamelet components. Advanced objects continues to create registry beans;
the Bean component's invocation defaults are a separate component resource. Existing saved
definitions and selections are never rewritten. Metadata loading/errors are shown before
component categories so resources do not briefly appear in the wrong group.

Native databases are configured only in Ignition Connections. DataSource object
selectors list their native names and save `ignition.database/<name>`. There is
no database subgrid, creation action, or integration resource group. Existing
DATABASE aliases remain editable under Advanced objects for compatibility.

## 4. Accessibility floor

Tab strips are ARIA tablists with roving tabindex; every control has a visible focus ring; icon-only
buttons carry aria-labels; tables scroll horizontally inside `.camel-scroll`; the Library tab strip
scrolls horizontally under 640px instead of wrapping into two rows.

## 5. Native grid filtering

Library categories filter the native resource list with `transformData`. Filtered grids request
all matches, retain native search and sort, and omit type-wide pagination totals so a category
never displays another category's count. Templates retain native pagination.
