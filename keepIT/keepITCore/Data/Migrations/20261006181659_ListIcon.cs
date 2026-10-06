using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace keepITCore.Data.Migrations
{
    /// <inheritdoc />
    public partial class ListIcon : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "Icon",
                table: "Lists",
                type: "character varying(16)",
                maxLength: 16,
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Icon",
                table: "Lists");
        }
    }
}
