using System.Net;
using keepITCore.Infrastructure;
using keepITCore.Tests.TestHost;
using Microsoft.AspNetCore.Http;
using Microsoft.Data.Sqlite;
using Serilog.Events;

namespace keepITCore.Tests;

/// <summary>
/// <c>/api/health</c>, which the image's Docker HEALTHCHECK asks every 30 seconds: healthy while
/// the database and the data folder are there, unhealthy when either is gone, and quiet in the log
/// while it's healthy.
/// </summary>
public sealed class HealthTests
{
    [Fact]
    public async Task A_running_server_is_healthy_and_says_no_more()
    {
        using var api = new KeepItApiFactory();
        using var client = api.CreateClient();

        var response = await client.GetAsync(HealthEndpoint.Path);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("Healthy", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task A_server_whose_data_folder_is_gone_is_unhealthy()
    {
        using var api = new KeepItApiFactory();
        using var client = api.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync(HealthEndpoint.Path)).StatusCode);

        SqliteConnection.ClearAllPools(); // Windows won't delete an open database file
        Directory.Delete(api.DataRoot, recursive: true);
        var response = await client.GetAsync(HealthEndpoint.Path);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.Equal("Unhealthy", await response.Content.ReadAsStringAsync());
    }

    [Theory]
    [InlineData("/api/health", 200, LogEventLevel.Verbose)]
    [InlineData("/api/health", 503, LogEventLevel.Error)]
    [InlineData("/api/notes", 200, LogEventLevel.Information)]
    [InlineData("/api/notes", 404, LogEventLevel.Information)]
    [InlineData("/api/notes", 500, LogEventLevel.Error)]
    public void Only_a_healthy_answer_stays_out_of_the_request_log(string path, int status, LogEventLevel expected)
    {
        var http = new DefaultHttpContext();
        http.Request.Path = path;
        http.Response.StatusCode = status;

        Assert.Equal(expected, HealthEndpoint.RequestLogLevel(http, 1, null));
    }
}
