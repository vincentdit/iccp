# ADR-012: A custom design system following Apple's Human Interface Guidelines, instead of Angular Material

**Status:** Accepted, October 2026. **Changes:** Doc 05 §4 named Angular Material.

## Context

Management asked for an interface that prioritises clarity, fluid motion and physical depth, guided by Apple's
Human Interface Guidelines (HIG). Angular Material implements Google's Material Design, whose components,
shapes, ripples and motion differ from the HIG.

## Decision

Build a small design system in the Angular app (`src/styles.scss` and `src/app/ui/`):

* **Clarity:** the system type ramp (large title 34 … caption 12) using San Francisco where installed and a bundled
  Inter elsewhere; semantic colours with light, dark and increased-contrast variants; 44 px minimum touch targets.
* **Deference and depth:** inset grouped lists; translucent "glass" sidebar, toolbars, tab bar and sheets that content
  scrolls beneath; three elevation levels; concentric corner radii.
* **Motion:** spring curves for state changes (segmented control, switches, sheets, the settlement ring), view
  transitions between pages, all disabled under reduced motion.
* **Accessibility settings in the app:** appearance, increase contrast, motion and a text-size slider (the web analogue of
  Dynamic Type), stored per device.
* **Icons:** Lucide (ISC licence). SF Symbols may only be used on Apple platforms, so it is not used on the web.

## Consequences

* About 20 components to maintain instead of a library. They are small and cover R1; later releases add to them.
* No dependency on Material's release cycle; the production bundle is about 424 kB (112 kB compressed) on first load.
* Accessibility is our responsibility: every control has a visible focus ring and an accessible name, and status
  is never shown by colour alone.
