import listIconsJson from './listIcons.json';

/**
 * The emoji a list can wear, in the order the picker shows them (rows of eight, loosely by theme:
 * home, work, money and health, travel, leisure, the rest).
 *
 * Kept as JSON rather than TypeScript because the Android app offers the same grid and its
 * ListIconsParityTest reads this file. The server doesn't hold icons to this set, only to "one
 * symbol" (`Lists/ListIcon.cs`), so a list keeps whatever icon it has if one is ever dropped from
 * here, and the picker still shows it as the current one.
 */
export const LIST_ICONS: readonly string[] = listIconsJson;
