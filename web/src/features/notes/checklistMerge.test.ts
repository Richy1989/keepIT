import { describe, expect, it } from 'vitest';
import type { ChecklistItemDto } from '../../api/types';
import cases from './checklistMerge.json';
import { mergeChecklist } from './checklistMerge';

/** A row as the cases write it: an id (or null), its text, and only what isn't the default. */
type CaseRow = { id: string | null; text: string; isChecked?: boolean; indent?: number };

const dtos = (rows: CaseRow[]): ChecklistItemDto[] =>
  rows.map((r, order) => ({ id: r.id, text: r.text, isChecked: r.isChecked ?? false, indent: r.indent ?? 0, order }));

const shape = (rows: ChecklistItemDto[]) =>
  rows.map((r) => ({ id: r.id ?? null, text: r.text, isChecked: r.isChecked, indent: r.indent ?? 0 }));

/**
 * Two people working one checklist at once, each saving the rows they started from with their own.
 * The cases live in `checklistMerge.json` because the server's `ChecklistMerge.cs` and the Android
 * app's `ChecklistMerge.kt` are tested against the same ones: a save merges the same everywhere.
 */
describe('mergeChecklist', () => {
  it.each(cases as { name: string; base: CaseRow[]; current: CaseRow[]; edit: CaseRow[]; merged: CaseRow[] }[])(
    '$name',
    ({ base, current, edit, merged }) => {
      const result = mergeChecklist(dtos(base), dtos(edit), dtos(current));

      expect(shape(result)).toEqual(shape(dtos(merged)));
      expect(result.map((r) => r.order)).toEqual(result.map((_, i) => i));
    },
  );
});
