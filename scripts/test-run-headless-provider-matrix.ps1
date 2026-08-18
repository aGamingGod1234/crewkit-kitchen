[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'run-headless-provider-matrix.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
	throw 'Expected lifecycle wrapper to exist'
}

function Assert-Fails([scriptblock] $Action, [string] $Pattern) {
	try {
		& $Action 2>&1 | Out-Null
		throw "Expected failure matching '$Pattern'"
	} catch {
		if ($_.Exception.Message -notmatch $Pattern) {
			throw "Failure did not match '$Pattern': $($_.Exception.Message)"
		}
	}
}

function Set-TestEnvironment([string] $Name, [string] $Value) {
	[Environment]::SetEnvironmentVariable($Name, $Value)
}

function New-Fixture([string] $Root) {
	New-Item -ItemType Directory -Path (Join-Path $Root 'build\libs'), (Join-Path $Root 'runtime\server-template\mods'), (Join-Path $Root 'runtime\server-template\logs'), (Join-Path $Root 'runtime\server-template\world'), (Join-Path $Root 'coordinator\config') -Force | Out-Null
	Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\coordinator\src') -Destination (Join-Path $Root 'coordinator\src') -Recurse -Force
	Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\coordinator\node_modules\acorn') -Destination (Join-Path $Root 'coordinator\node_modules\acorn') -Recurse -Force
	$fakeCodex = Join-Path $Root 'fake-appdata\npm\node_modules\@openai\codex\bin\codex.js'
	New-Item -ItemType Directory -Path (Split-Path -Parent $fakeCodex) -Force | Out-Null
@'
import readline from 'node:readline';
const input = readline.createInterface({ input: process.stdin });
input.on('line', (line) => {
    try {
        const request = JSON.parse(line);
        if (!Number.isInteger(request.id)) return;
        const result = request.method === 'model/list' ? { data: [], nextCursor: null } : {};
        process.stdout.write(`${JSON.stringify({ id: request.id, result })}\n`);
    } catch {}
});
'@ | Set-Content -LiteralPath $fakeCodex -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'build\libs\arena-agents-0.1.0.jar') -Value 'fixture' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\fabric-server-launch.jar') -Value 'not-a-real-jar' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\world\stale.dat') -Value 'must not be copied' -NoNewline
	$dynamicConfig = [pscustomobject]@{
		bridge = [pscustomobject]@{ host = '127.0.0.1'; port = 25570; secretEnvironmentVariable = 'ARENA_AGENT_BRIDGE_SECRET' }
		codex = [pscustomobject]@{
			launchProfile = [pscustomobject]@{ model = 'fixture'; reasoningEffort = 'low'; serviceTier = 'fast' }
			environment = [pscustomobject]@{ APPDATA = (Join-Path $Root 'fake-appdata') }
		}
		limits = [pscustomobject]@{ agentCap = 1; goalQueueCap = 1; planningConcurrency = 1 }
	}
	Set-Content -LiteralPath (Join-Path $Root 'coordinator\config\dynamic-agents.json') -Value ($dynamicConfig | ConvertTo-Json -Depth 8) -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'matrix.json') -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
}

function Enable-FakeServer([string] $Root) {
	$source = Join-Path $Root 'FakeServer.java'
	$classes = Join-Path $Root 'fake-classes'
	$jar = Join-Path $Root 'runtime\server-template\fabric-server-launch.jar'
	New-Item -ItemType Directory -Path $classes -Force | Out-Null
@'
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FakeServer {
    private static int readLittleEndian(InputStream input) throws IOException {
        int b0 = input.read();
        if (b0 < 0) return -1;
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b1 | b2 | b3) < 0) throw new IOException("truncated packet length");
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readLittleEndian(byte[] payload, int offset) {
        return (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8)
            | ((payload[offset + 2] & 0xff) << 16) | ((payload[offset + 3] & 0xff) << 24);
    }

    private static void writeLittleEndian(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeResponse(OutputStream output, int id, int type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[10 + body.length];
        writeLittleEndian(payload, 0, id);
        writeLittleEndian(payload, 4, type);
        System.arraycopy(body, 0, payload, 8, body.length);
        writeLittleEndian(output, payload.length);
        output.write(payload);
    }

    private static void serveRcon(Socket socket, AtomicBoolean running) {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            while (running.get()) {
                int length = readLittleEndian(input);
                if (length < 0) return;
                if (length < 10 || length > 1_048_576) throw new IOException("invalid RCON packet");
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) throw new IOException("truncated RCON packet");
                int id = readLittleEndian(payload, 0);
                int type = readLittleEndian(payload, 4);
                String command = new String(payload, 8, length - 10, StandardCharsets.UTF_8);
                String response = type == 3 ? "" : command.contains("summon-configured") ? "ERROR fake server" : "state=ERROR";
                writeResponse(output, id, type == 3 ? 2 : 0, response);
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static int bridgePort() throws IOException {
        String config = Files.readString(Path.of("..", "coordinator-config.json"));
        Matcher matcher = Pattern.compile("\\\"bridge\\\"\\s*:\\s*\\{[^}]*\\\"port\\\"\\s*:\\s*(\\d+)").matcher(config);
        if (!matcher.find()) throw new IOException("bridge port missing from fixture config");
        return Integer.parseInt(matcher.group(1));
    }

    private static void serveBridge(Socket socket, AtomicBoolean running) {
        try (socket; BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); OutputStream output = socket.getOutputStream()) {
            String hello = reader.readLine();
            if (hello == null) return;
            Matcher messageId = Pattern.compile("\\\"messageId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(hello);
            if (!messageId.find()) return;
            String response = "{\"protocolVersion\":2,\"serverInstanceId\":\"fake-server\",\"agentId\":\"server\",\"type\":\"hello_ack\",\"messageId\":\"fake-ack\",\"payload\":{\"replyTo\":\"" + messageId.group(1) + "\",\"authenticated\":true,\"registry\":[]}}\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (running.get() && reader.readLine() != null) { }
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("server.properties"))) { properties.load(input); }
        int serverPort = Integer.parseInt(properties.getProperty("server-port"));
        int rconPort = Integer.parseInt(properties.getProperty("rcon.port"));
        int bridgePort = bridgePort();
        AtomicBoolean running = new AtomicBoolean(true);
        ServerSocket minecraft = new ServerSocket(serverPort, 16, InetAddress.getLoopbackAddress());
        ServerSocket rcon = new ServerSocket(rconPort, 16, InetAddress.getLoopbackAddress());
        ServerSocket bridge = new ServerSocket(bridgePort, 16, InetAddress.getLoopbackAddress());
        Files.createDirectories(Path.of("logs"));
        Files.writeString(Path.of("logs", "latest.log"), "Done (0.1s)!\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 30").inheritIO().start();
        Thread acceptor = new Thread(() -> {
            while (running.get()) {
                try { serveRcon(rcon.accept(), running); } catch (IOException ignored) { return; }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        Thread bridgeAcceptor = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket client = bridge.accept();
                    new Thread(() -> serveBridge(client, running)).start();
                } catch (IOException ignored) { return; }
            }
        });
        bridgeAcceptor.setDaemon(true);
        bridgeAcceptor.start();
        Thread stopReader = new Thread(() -> {
            try { new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine(); } catch (IOException ignored) { }
            running.set(false);
            try { minecraft.close(); } catch (IOException ignored) { }
            try { rcon.close(); } catch (IOException ignored) { }
            try { bridge.close(); } catch (IOException ignored) { }
        });
        stopReader.setDaemon(true);
        stopReader.start();
        while (running.get()) Thread.sleep(100L);
    }
}
'@ | Set-Content -LiteralPath $source -NoNewline
	$javac = (Get-Command javac -ErrorAction Stop).Source
	$jarTool = 'C:\Program Files\Java\jdk-25\bin\jar.exe'
	if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "Missing test JAR tool: $jarTool" }
	& $javac -d $classes $source
	if ($LASTEXITCODE -ne 0) { throw 'Could not compile dummy Fabric server fixture' }
	& $jarTool --create --file $jar --main-class FakeServer -C $classes FakeServer.class
	if ($LASTEXITCODE -ne 0) { throw 'Could not package dummy Fabric server fixture' }
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' ((Get-Command java -ErrorAction Stop).Source)
}

function Stop-TestProcessTree([int] $ProcessId) {
	$children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
	foreach ($child in $children) { Stop-TestProcessTree ([int] $child.ProcessId) }
	Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Test-PortClosed([int] $Port) {
	return $null -eq (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

$project = Join-Path ([IO.Path]::GetTempPath()) "arena-headless-wrapper-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $project -Force | Out-Null
try {
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' (Join-Path $project 'not-java.exe')
	Assert-Fails { & $scriptPath -ProjectRoot $project } 'Java 25|prerequisite|missing'
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' $null

	$missingTemplateProject = Join-Path $project 'missing-template'
	New-Item -ItemType Directory -Path $missingTemplateProject -Force | Out-Null
	Assert-Fails { & $scriptPath -ProjectRoot $missingTemplateProject -ServerTemplate (Join-Path $missingTemplateProject 'no-server') } 'template|server'

	$fixture = Join-Path $project 'fixture'
	New-Fixture $fixture
	Set-TestEnvironment 'ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39165'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39166'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39167'
	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '1'
	$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 39165)
	$listener.Start()
	try {
		Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'occupied|already|port'
	} finally {
		$listener.Stop()
	}
	Write-Output 'PASS occupied-port validation starts no provider process'

	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39165'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'distinct|duplicate|port'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39166'
	Write-Output 'PASS duplicate configured ports are rejected'
	$unsafeMatrix = Join-Path $fixture 'unsafe-matrix.json'
	Set-Content -LiteralPath $unsafeMatrix -Value '{"version":1,"scenarios":[{"id":"../escape","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath $unsafeMatrix -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'safe|separator|scenario ID|control'

	Enable-FakeServer $fixture
	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '5'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'ready|timed out|failed|required'
	if (-not (Test-PortClosed 39165) -or -not (Test-PortClosed 39166) -or -not (Test-PortClosed 39167)) { throw 'Allocated ports remained open after timeout cleanup' }
	$runRoot = Join-Path $fixture 'runtime\headless-runs'
	$reports = @(Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json -ErrorAction SilentlyContinue)
	if ($reports.Count -lt 1) { throw 'Timeout cleanup did not write a scenario report' }
	if (@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'FakeServer|Start-Sleep -Seconds 3' }).Count -gt 0) { throw 'Wrapper cleanup left dummy server descendants running' }
	$latestMatrixReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter matrix-report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$matrixObject = Get-Content -Raw -LiteralPath $latestMatrixReport.FullName | ConvertFrom-Json
	if ($matrixObject.status -ne 'FAILED' -or @($matrixObject.scenarios).Count -ne 1 -or $matrixObject.scenarios[0].status -ne 'FAILED') { throw 'matrix-report.json did not forward the failed runner status' }
	$latestManifest = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter matrix-manifest.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$manifestText = Get-Content -Raw -LiteralPath $latestManifest.FullName
	if ($manifestText -match '"(task|message|args|state)"\s*:') { throw 'matrix-manifest.json retained unbounded task or assertion payload' }
	$manifestObject = $manifestText | ConvertFrom-Json
	if ($manifestObject.scenarioCount -ne 1 -or @($manifestObject.scenarios).Count -ne 1) { throw 'matrix-manifest.json was not a bounded scenario summary' }
	$latestScenarioReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$defaultScenarioDirectory = Split-Path -Parent $latestScenarioReport.FullName
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'rcon-password.txt')) { throw 'Default cleanup retained the RCON credential' }
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'server')) { throw 'Default cleanup retained the copied server' }
	if (Test-Path -LiteralPath (Join-Path $defaultScenarioDirectory 'provider-workspaces')) { throw 'Default cleanup retained provider workspaces' }

	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39168'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39169'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39170'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') -KeepArtifacts } 'ready|timed out|failed|required'
	$keptMatrixReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter matrix-report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$keptScenarioReport = Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
	$keptScenarioDirectory = Split-Path -Parent $keptScenarioReport.FullName
	if (-not (Test-Path -LiteralPath (Join-Path $keptScenarioDirectory 'rcon-password.txt'))) { throw 'KeepArtifacts did not retain the RCON credential artifact' }
	$properties = Get-Content -Raw -LiteralPath (Join-Path $keptScenarioDirectory 'server\server.properties')
	if ($properties -notmatch '(?m)^rcon\.ip=127\.0\.0\.1\r?$') { throw "RCON loopback binding was not configured: $properties" }
	Write-Output 'PASS matrix report forwarding, cleanup retention, KeepArtifacts, and loopback RCON'
	$copiedWorlds = @(Get-ChildItem -LiteralPath $runRoot -Recurse -Directory -Filter world -ErrorAction SilentlyContinue)
	if ($copiedWorlds.Count -gt 0) { throw 'Server template world was copied into a scenario' }

	$dummyScript = Join-Path $project 'dummy-child-tree.ps1'
	Set-Content -LiteralPath $dummyScript -Value "Start-Process powershell -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 30'`nStart-Sleep -Seconds 30" -NoNewline
	$dummyRoot = Start-Process powershell -ArgumentList '-NoProfile','-File',$dummyScript -PassThru
	try {
		Start-Sleep -Milliseconds 500
		Stop-TestProcessTree $dummyRoot.Id
		Start-Sleep -Milliseconds 250
		if (-not $dummyRoot.HasExited) { throw 'Dummy child-tree root survived cleanup' }
	} finally {
		Stop-TestProcessTree $dummyRoot.Id
	}

	$wrapperText = Get-Content -Raw -LiteralPath $scriptPath
	foreach ($requiredPattern in @('Stop-ProcessTree', 'Get-ProcessTreeIds', 'Get-CimInstance Win32_Process', 'Wait-Condition', 'Test-Port', 'ARENA_AGENT_BRIDGE_SECRET', 'provider-workspaces', 'cleanupFailure', 'artifactsKept', 'rcon.ip')) {
		if ($wrapperText -notmatch [regex]::Escape($requiredPattern)) { throw "Lifecycle wrapper missing cleanup/isolation hook '$requiredPattern'" }
	}
	Write-Output 'PASS timeout cleanup, port verification, child-tree cleanup, and provider isolation hooks'
} finally {
	foreach ($name in @('ARENA_HEADLESS_JAVA','ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT','ARENA_HEADLESS_MINECRAFT_PORT','ARENA_HEADLESS_RCON_PORT','ARENA_HEADLESS_BRIDGE_PORT','ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS')) { Set-TestEnvironment $name $null }
	if (Test-Path -LiteralPath $project) {
		Remove-Item -LiteralPath $project -Recurse -Force
	}
}
