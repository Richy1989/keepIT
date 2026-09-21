using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// A minimal SMTP server on loopback that records every line it receives. It never offers STARTTLS,
/// so it stands in for a real server whose offer someone on the network path has stripped — the
/// downgrade that opportunistic STARTTLS falls for.
/// </summary>
public sealed class FakeSmtpServer : IAsyncDisposable
{
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly Task _acceptLoop;

    /// <summary>Every command and message line received, in order.</summary>
    public ConcurrentQueue<string> Received { get; } = new();

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

    public FakeSmtpServer()
    {
        _listener.Start();
        _acceptLoop = AcceptAsync();
    }

    private async Task AcceptAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(_stop.Token); }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { return; }
            _ = Task.Run(() => ServeAsync(client));
        }
    }

    private async Task ServeAsync(TcpClient client)
    {
        using var _ = client;
        var stream = client.GetStream();
        using var reader = new StreamReader(stream, Encoding.ASCII);
        await using var writer = new StreamWriter(stream, Encoding.ASCII) { NewLine = "\r\n", AutoFlush = true };

        try
        {
            await writer.WriteLineAsync("220 fake.test ESMTP");
            while (await reader.ReadLineAsync() is { } line)
            {
                Received.Enqueue(line);
                switch (line.Split(' ')[0].ToUpperInvariant())
                {
                    // Capabilities without STARTTLS: authentication is on offer, encryption is not.
                    case "EHLO":
                        await writer.WriteAsync("250-fake.test\r\n250 AUTH PLAIN LOGIN\r\n");
                        break;
                    case "AUTH":
                        await writer.WriteLineAsync("235 2.7.0 Authentication successful");
                        break;
                    case "DATA":
                        await writer.WriteLineAsync("354 End data with <CR><LF>.<CR><LF>");
                        while (await reader.ReadLineAsync() is { } body && body != ".")
                            Received.Enqueue(body);
                        await writer.WriteLineAsync("250 OK queued");
                        break;
                    case "QUIT":
                        await writer.WriteLineAsync("221 Bye");
                        return;
                    default:
                        await writer.WriteLineAsync("250 OK");
                        break;
                }
            }
        }
        catch (IOException)
        {
            // The client hung up mid-conversation — what a refused downgrade looks like from here.
        }
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _listener.Stop();
        await _acceptLoop;
        _stop.Dispose();
    }
}
