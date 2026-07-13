using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class NoteReminders : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "ReminderNoteId",
                table: "Notifications",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "ReminderNoteTitle",
                table: "Notifications",
                type: "character varying(1000)",
                maxLength: 1000,
                nullable: true);

            migrationBuilder.CreateTable(
                name: "NoteReminders",
                columns: table => new
                {
                    NoteId = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    RemindAtUtc = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    Recurrence = table.Column<int>(type: "integer", nullable: false),
                    FiredAtUtc = table.Column<DateTime>(type: "timestamp with time zone", nullable: true),
                    CreatedAtUtc = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NoteReminders", x => new { x.NoteId, x.UserId });
                    table.ForeignKey(
                        name: "FK_NoteReminders_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_NoteReminders_Notes_NoteId",
                        column: x => x.NoteId,
                        principalTable: "Notes",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_NoteReminders_RemindAtUtc",
                table: "NoteReminders",
                column: "RemindAtUtc");

            migrationBuilder.CreateIndex(
                name: "IX_NoteReminders_UserId",
                table: "NoteReminders",
                column: "UserId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "NoteReminders");

            migrationBuilder.DropColumn(
                name: "ReminderNoteId",
                table: "Notifications");

            migrationBuilder.DropColumn(
                name: "ReminderNoteTitle",
                table: "Notifications");
        }
    }
}
