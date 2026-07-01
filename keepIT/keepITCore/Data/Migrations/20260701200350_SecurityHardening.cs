using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class SecurityHardening : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddForeignKey(
                name: "FK_NoteLists_AspNetUsers_UserId",
                table: "NoteLists",
                column: "UserId",
                principalTable: "AspNetUsers",
                principalColumn: "Id",
                onDelete: ReferentialAction.Cascade);

            migrationBuilder.AddForeignKey(
                name: "FK_NoteUserStates_AspNetUsers_UserId",
                table: "NoteUserStates",
                column: "UserId",
                principalTable: "AspNetUsers",
                principalColumn: "Id",
                onDelete: ReferentialAction.Cascade);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_NoteLists_AspNetUsers_UserId",
                table: "NoteLists");

            migrationBuilder.DropForeignKey(
                name: "FK_NoteUserStates_AspNetUsers_UserId",
                table: "NoteUserStates");
        }
    }
}
