# Mydes Browser

A lightweight, distraction-free Android web browser engineered for fast load times and clean ergonomics. Mydes minimizes visual clutter by eliminating persistent toolbars in favor of gesture-driven plateau micro-notches, an expandable geometric options pill with custom shape morphing, and an adaptive top address capsule that dynamically adopts website brand colors.

***

## User Review & Critical Decisions

> [!IMPORTANT]
> The implementation plan has been revised with your exact specifications:

- **Confirmed Decision 1 (Top Header Pill Adaptive Color Logic)**:
  - The background of the top header pill is **always** the website's primary colour (even if white or near-white).
  - The webpage title text is rendered in **white** (`#FFFFFF`) by default, and automatically switches to **black** (`#000000`) if the website's primary colour (and hence the pill background) is white or near-white (high luminance > 0.85).
  - Settings and Brush icons retain their light grey styling.
- **Confirmed Decision 2 (Plateau Notches)**:
  - Notches are designed as **tiny plateaus** centered at the screen edges, rather than thin lines.
  - **Top Notch**: Tiny plateau in the center top with 10% opacity; dragging down reveals the all-tabs grid.
  - **Bottom Notch**: Tiny plateau in the center bottom; dragging up triggers the card stack tab creation.
- **Confirmed Decision 3 (Bottom Notch Card Stack & New Incognito Tab)**:
  - Pulling the bottom notch up causes the current tab to size down and move farther back like a card in a card stack, with a new tab card descending from the top.
  - An incognito badge is visible on the right of the receding card stack during this gesture.
  - Pulling up and pushing to the right spawns an **originally new incognito tab** (preserving the active standard tab in the stack).
- **Confirmed Decision 4 (Geometric Options Button Morphing Animation)**:
  - Default state: 3 distinct shapes aligned vertically (Circle at top, Triangle in middle, Square at bottom).
  - Tapping to activate morphs the shapes into distinct lines:
    - **Circle** spans outward into a line with semi-circular ends.
    - **Triangle** spans outward into a line with slanting ends.
    - **Square** spans outward into a line with flat ends.
- **Confirmed Decision 5 (Top Slim Progress Bar & Dynamic Website Theming)**:
  - Slim progress line fixed at the very top of the screen.
  - Dynamically colored using the primary brand color of the website being loaded.
  - While extracting or if unknown, runs an animated magical multicolor (rainbow shimmer) sweep; as soon as the primary color is resolved (via HTML theme-color / favicon / website cache), it instantly snaps to that solid brand color.
  - Pre-seeded cache of famous websites (Google, YouTube, GitHub, Reddit, Wikipedia, Twitter/X, Amazon, DuckDuckGo) + persistent local caching of extracted primary colors for visited domains.
- **Confirmed Decision 6 (Strict Monochrome Palette)**:
  - Strictly grey and black shades; no purple.
  - Incognito accent color: `#171717`.
  - Expandable options pill background: White in light mode, `#171717` in incognito mode, surrounded by a distinct dark black border.
  - Buttons/icons inside the options pill: Black (`#000000`) accent.
  - Settings, Brush, and Options outer button: Light grey styling.

***

## 1. Overview & Core Concept

- **What It Does**: Full-bleed web browsing with zero screen obstruction. Subtle plateau notches at the top and bottom enable gesture-based navigation and tab management, while an expandable geometric trigger unveils essential browser actions.
- **Target Persona**: Users looking for a swift, lightweight, zero-bloat browser with intuitive gestures, fast page loading, and private browsing.
- **Key Value**: 100% viewport space for web content, dynamic website-aware visual theming, and smooth card-stack multi-tab transitions.

***

## 2. User Experience & Visual Design

### Key User Flows

1. **Browsing & Dynamic Progress**:
   - Loading any page displays the top slim progress bar in the website's primary color (or magical multicolor shimmer if unknown until extracted).
   - Once loaded, the top bar disappears, leaving an unobstructed full screen.
2. **Options Menu & Geometric Morphing**:
   - Tapping the bottom-right geometric button morphs its 3 shapes into lines (Circle -> rounded-ended line, Triangle -> slanted-ended line, Square -> flat-ended line).
   - The 9-button vertical pill expands upward: Copy Link, History, Downloads, Bookmarks, Share, Find in Page, Reading Mode, Desktop Site, Help.
   - Pill background is White (Light) or `#171717` (Incognito) with a solid black border and black icons.
   - The top header pill expands with the website's primary color background. The title text is white, or black if the site's primary color is white/near-white.
3. **Card Stack Notch Gestures & Tab Creation**:
   - **Bottom Plateau Notch**:
     - Pulling up shrinks and recedes the active tab like a card in a deck.
     - An incognito badge appears on the right of the receding card.
     - Releasing straight up drops a new standard tab card from the top.
     - Pushing right before releasing opens a **new incognito tab**.
   - **Top Plateau Notch (10% Opacity)**:
     - Pulling down from the top 10% opacity plateau reveals the visual grid of all current tabs with previews and close buttons.
4. **Start Page**:
   - Clean centered search pill (Google Search) with an organic, randomized icon grid of frequently visited sites.

### Visual Identity & Theme Tokens

- `Light Surface`: Light grey (`#E0E0E0` / `#EEEEEE`) for floating buttons.
- `Plateau Notches`: Tiny plateau tabs centered at the top (10% opacity) and bottom.
- `Options Menu Pill`: Solid White (`#FFFFFF`) / `#171717` with distinct dark black border (`#000000`, 1.5.dp stroke).
- `Pill Action Icons`: Pure Black (`#000000`).
- `Incognito Accent`: `#171717`.
- `Top Header Pill Background`: Website primary colour always.
- `Top Header Pill Title`: White (`#FFFFFF`) by default, Black (`#000000`) if background is white or near-white.

***

## 3. Key Product Decisions & Trade-Offs

- **Adaptive Header Contrast Engine**:
  - *Chosen Approach*: Calculate WCAG luminance of the website's primary color: if `luminance > 0.85`, title text uses `#000000`, otherwise `#FFFFFF`. Header pill background remains the exact primary color.
  - *Why*: Satisfies exact user rule while ensuring crisp legibility regardless of website branding.
- **Plateau Shape Geometry**:
  - *Chosen Approach*: Drawn via Compose `Path` with rounded chamfers forming an inverted plateau (top) and upright plateau (bottom), giving a physical tab affordance.
- **3D Card Stack Gesture**:
  - *Chosen Approach*: Physics-based drag tracking that drives scale (1.0 -> 0.78), translationY, translationZ, elevation shadow, and a lateral incognito badge indicator.

***

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│                      Mydes Browser                     │
│                                                        │
│  [ Slim Top Progress Line: Dynamic Brand Color/Rainbow]│
│  ┌──────────────────────────────────────────────────┐  │
│  │     Top Plateau Notch (10% Opacity) [Tabs]       │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Top Header Pill: [Settings | Title Text | Brush] │  │
│  │ Background: Site Primary Color                   │  │
│  │ Title: White (or Black if background near-white) │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │                                                  │  │
│  │            Active Tab WebView / Start Page       │  │
│  │      (Sizes down like Card on Bottom Drag)       │  │
│  │                                                  │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌─────────────────────────┐  ┌─────────────────────┐  │
│  │ Bottom Plateau Notch    │  │ Expandable Pill     │  │
│  │ Pull: Card recedes      │  │ White/Dark Pill +   │  │
│  │ Right: Fresh Incognito  │  │ Black Icons         │  │
│  │ Straight: Fresh Tab     │  │ [Morphing Button]   │  │
│  └─────────────────────────┘  └─────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

### Component Breakdown

1. `MainActivity.kt`: Edge-to-edge layout, system back handler, window insets.
2. `BrowserViewModel.kt`: Tab collection, active tab, incognito session segregation, options pill state, address bar state, search queries.
3. `PlateauNotch.kt`: Custom-drawn plateau shape composable for top (10% opacity) and bottom with drag gesture detectors.
4. `MorphingOptionsButton.kt`: Canvas-rendered vertical shape-to-line morphing (circle->capsule, triangle->slanted, square->flat).
5. `TopHeaderPill.kt`: Website-colored capsule with luminance-aware title text (white/black) and light grey tool buttons.
6. `OptionsPillMenu.kt`: Expandable 9-icon pill menu with black icons and dark black border.
7. `CardStackTabTransition.kt`: Card scaling, depth movement, incognito side badge, and new tab drop animation.
8. `WebsiteColorRepository.kt`: Seeded cache for famous sites, meta theme-color extraction, and Room persistence.
9. `data/`: Room entities for Bookmarks, History, and DomainColorCache.

***

## 5. Execution Steps

1. Update `metadata.json` and `strings.xml` for "Mydes Browser".
2. Implement Room entities and DAO for history, bookmarks, and domain colors.
3. Implement `WebsiteColorRepository` with seeded colors, dynamic extraction, and luminance detection.
4. Implement `PlateauNotch` with custom plateau shapes and gesture handlers.
5. Implement `MorphingOptionsButton` with the 3 distinct shape-to-line transitions.
6. Build `TopHeaderPill` adopting site primary color and black title fallback for light backgrounds.
7. Build `OptionsPillMenu` with white/`#171717` background, black border, and 9 black action icons.
8. Build `CardStackTabTransition` handling standard and fresh incognito tab creation.
9. Build `StartPage`, `TabOverviewSheet`, `BookmarksSheet`, `HistorySheet`, and `SettingsDialog`.
10. Verify compilation with `compile_applet`.
