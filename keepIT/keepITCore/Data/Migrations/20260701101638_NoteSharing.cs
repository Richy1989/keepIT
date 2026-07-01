using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class NoteSharing : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "Role",
                table: "Notifications",
                type: "integer",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "NoteShares",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    NoteId = table.Column<Guid>(type: "uuid", nullable: false),
                    GranteeId = table.Column<Guid>(type: "uuid", nullable: false),
                    Role = table.Column<int>(type: "integer", nullable: false),
                    CreatedByUserId = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAtUtc = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NoteShares", x => x.Id);
                    table.ForeignKey(
                        name: "FK_NoteShares_AspNetUsers_GranteeId",
                        column: x => x.GranteeId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_NoteShares_Notes_NoteId",
                        column: x => x.NoteId,
                        principalTable: "Notes",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "NoteUserStates",
                columns: table => new
                {
                    NoteId = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    IsPinned = table.Column<bool>(type: "boolean", nullable: false),
                    IsArchived = table.Column<bool>(type: "boolean", nullable: false),
                    IsTrashed = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_NoteUserStates", x => new { x.NoteId, x.UserId });
                    table.ForeignKey(
                        name: "FK_NoteUserStates_Notes_NoteId",
                        column: x => x.NoteId,
                        principalTable: "Notes",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_NoteShares_GranteeId",
                table: "NoteShares",
                column: "GranteeId");

            migrationBuilder.CreateIndex(
                name: "IX_NoteShares_NoteId_GranteeId",
                table: "NoteShares",
                columns: new[] { "NoteId", "GranteeId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_NoteUserStates_UserId",
                table: "NoteUserStates",
                column: "UserId");

            // Pin/archive/trash moved from the note to a per-user view. Preserve every existing note's
            // state as its owner's private view (one row per note) before the old columns are dropped —
            // without this, existing notes would have no NoteUserState row and vanish from the grid.
            migrationBuilder.Sql(
                @"INSERT INTO ""NoteUserStates"" (""NoteId"", ""UserId"", ""IsPinned"", ""IsArchived"", ""IsTrashed"")
                  SELECT ""Id"", ""OwnerId"", ""IsPinned"", ""IsArchived"", ""IsTrashed"" FROM ""Notes"";");

            migrationBuilder.DropColumn(name: "IsArchived", table: "Notes");
            migrationBuilder.DropColumn(name: "IsPinned", table: "Notes");
            migrationBuilder.DropColumn(name: "IsTrashed", table: "Notes");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "IsArchived",
                table: "Notes",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "IsPinned",
                table: "Notes",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "IsTrashed",
                table: "Notes",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            // Fold the owner's per-user view back onto the note before the per-user table is dropped.
            migrationBuilder.Sql(
                @"UPDATE ""Notes"" AS n
                  SET ""IsPinned"" = us.""IsPinned"", ""IsArchived"" = us.""IsArchived"", ""IsTrashed"" = us.""IsTrashed""
                  FROM ""NoteUserStates"" AS us
                  WHERE us.""NoteId"" = n.""Id"" AND us.""UserId"" = n.""OwnerId"";");

            migrationBuilder.DropTable(
                name: "NoteShares");

            migrationBuilder.DropTable(
                name: "NoteUserStates");

            migrationBuilder.DropColumn(
                name: "Role",
                table: "Notifications");
        }
    }
}
