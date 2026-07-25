import type { ChecklistItemDto } from '../../api/types';

/** A checklist row paired with its index in the stored (unsorted) array, so edits address the right item. */
export type ChecklistRow = { item: ChecklistItemDto; index: number };

/**
 * Display order for a checklist: **unchecked rows first, checked rows at the bottom**, each group
 * keeping the stored order.
 *
 * The stored array is the *home* order — it changes only when rows are added, removed, or dragged,
 * never when a box is ticked. So checking a row drops it to the bottom, unchecking puts it back in
 * exactly the slot it came from, and a freshly added row lands at the end of the unchecked block
 * (above the checked ones). Because that's derived from the persisted `order` + `isChecked` rather
 * than remembered in the client, it holds after a reload and on every device.
 *
 * The sort is stable (`Array.prototype.sort` is, per spec), so equal-checked rows keep their order.
 */
export function checklistRows(items: ChecklistItemDto[]): ChecklistRow[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => (a.item.isChecked ? 1 : 0) - (b.item.isChecked ? 1 : 0));
}

/** [checklistRows] for read-only views that don't need the stored index. */
export function checklistForDisplay(items: ChecklistItemDto[]): ChecklistItemDto[] {
  return checklistRows(items).map((r) => r.item);
}
