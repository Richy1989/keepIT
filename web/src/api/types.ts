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
export type SetNoteReminderDto = S['SetNoteReminderDto'];
export type ReminderRecurrence = S['ReminderRecurrence'];
export type SetNoteListsDto = S['SetNoteListsDto'];
export type ChecklistItemDto = S['ChecklistItemDto'];
export type NoteType = S['NoteType'];

export type NoteShareDto = S['NoteShareDto'];
export type CreateShareDto = S['CreateShareDto'];
export type UpdateShareRoleDto = S['UpdateShareRoleDto'];
export type NoteRole = S['NoteRole'];

export type UserNotificationDto = S['UserNotificationDto'];
export type ShareResponseDto = S['ShareResponseDto'];

export type ListDto = S['ListDto'];
export type CreateListDto = S['CreateListDto'];
export type UpdateListDto = S['UpdateListDto'];

export type UserSettingsDto = S['UserSettingsDto'];
export type TestEmailResultDto = S['TestEmailResultDto'];
export type MetaDto = S['MetaDto'];

export type AuthResponseDto = S['AuthResponseDto'];
export type UserDto = S['UserDto'];
export type LoginRequestDto = S['LoginRequestDto'];
export type RegisterRequestDto = S['RegisterRequestDto'];
