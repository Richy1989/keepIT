using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class ReminderTimeZone : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTime>(
                name: "FirstAtUtc",
                table: "NoteReminders",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "TimeZone",
                table: "NoteReminders",
                type: "character varying(100)",
                maxLength: 100,
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "FirstAtUtc",
                table: "NoteReminders");

            migrationBuilder.DropColumn(
                name: "TimeZone",
                table: "NoteReminders");
        }
    }
}
