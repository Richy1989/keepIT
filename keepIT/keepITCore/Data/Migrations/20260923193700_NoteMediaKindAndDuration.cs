using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class NoteMediaKindAndDuration : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "DurationMs",
                table: "NoteMedia",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "Kind",
                table: "NoteMedia",
                type: "integer",
                nullable: false,
                defaultValue: 0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "DurationMs",
                table: "NoteMedia");

            migrationBuilder.DropColumn(
                name: "Kind",
                table: "NoteMedia");
        }
    }
}
