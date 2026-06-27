import type { components } from './schema';

/**
 * Convenience aliases for the generated DTO types. These re-export the OpenAPI-derived shapes so
 * feature code imports `NoteDto` rather than the verbose `components['schemas']['NoteDto']`.
 *
 * Do NOT hand-edit the shapes — they come from the C# DTOs via `npm run generate:api`.
 */
type S = components['schemas'];

export type NoteDto = S['NoteDto'];
export type CreateNoteDto = S['CreateNoteDto'];
export type UpdateNoteDto = S['UpdateNoteDto'];
export type NoteStateDto = S['NoteStateDto'];
export type SetNoteListsDto = S['SetNoteListsDto'];
export type ChecklistItemDto = S['ChecklistItemDto'];
export type NoteType = S['NoteType'];

export type ListDto = S['ListDto'];
export type CreateListDto = S['CreateListDto'];
export type UpdateListDto = S['UpdateListDto'];

export type UserSettingsDto = S['UserSettingsDto'];

export type AuthResponseDto = S['AuthResponseDto'];
export type UserDto = S['UserDto'];
export type LoginRequestDto = S['LoginRequestDto'];
export type RegisterRequestDto = S['RegisterRequestDto'];
