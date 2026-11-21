"""Dedicated 26.3 smoke test on a new localhost flat save with two vanilla protocol sessions."""
import argparse
import contextlib
import hashlib
import io
import json
import math
import re
import os
import secrets
import shutil
import socket
import sys
import struct
import subprocess
import time
import uuid
import zipfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build


PROTOCOL = 777
RUNNER = "BoundaryRunner"
GUEST = "BoundaryGuest"
FLOOR_Y = -60.0


def packet_int(value):
    value &= 0xffffffff
    parts = bytearray()
    while value > 127:
        parts.append((value & 127) | 128)
        value >>= 7
    parts.append(value)
    return bytes(parts)


def exact(stream, size):
    received = stream.read(size)
    if len(received) != size:
        raise EOFError(f"Expected {size} bytes, received {len(received)}")
    return received


def read_int(stream):
    value = 0
    for shift in range(0, 35, 7):
        byte = exact(stream, 1)[0]
        value |= (byte & 127) << shift
        if byte < 128:
            return value
    raise ValueError("Minecraft VarInt is too long")


def string(content):
    encoded = content.encode()
    return packet_int(len(encoded)) + encoded



def offline_id(name):
    return uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{name}".encode()).digest(), version=3)


class ProbeClient:
    def __init__(self, port, packets):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=30)
        self.stream = self.socket.makefile("rb")
        self.packets = packets
        self.phase = "handshake"

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.stream.close()
        self.socket.close()

    def send(self, name, payload=b""):
        packet_id = self.packets[self.phase]["serverbound"][f"minecraft:{name}"]["protocol_id"]
        data = packet_int(packet_id) + payload
        self.socket.sendall(packet_int(len(data)) + data)

    def join(self, port, protocol, name):
        handshake = next(iter(self.packets["handshake"]["serverbound"])).split(":")[1]
        self.send(handshake, packet_int(protocol) + string("localhost") + struct.pack(">H", port) + packet_int(2))
        self.phase = "login"
        self.send("hello", string(name) + offline_id(name).bytes)
        self.wait("login_finished")
        self.send("login_acknowledged")
        self.phase = "configuration"
        self.wait("select_known_packs")
        self.send("select_known_packs", b"\0")
        self.wait("finish_configuration")
        self.send("finish_configuration")
        self.phase = "play"
        self.wait("player_position")
        self.send("player_loaded")

class Client(ProbeClient):
    def __init__(self, *args):
        super().__init__(*args)
        self.position = (0.0, 0.0, 0.0)
        self.rotation = (0.0, 0.0)
        self.last_teleport = None

    def accept_position(self, data):
        packet = io.BytesIO(data)
        teleport_id = read_int(packet)
        if teleport_id == self.last_teleport:
            return False
        self.last_teleport = teleport_id
        coords = struct.unpack(">ddd", exact(packet, 24))
        exact(packet, 24)
        rotation = struct.unpack(">ff", exact(packet, 8))
        flags = struct.unpack(">I", exact(packet, 4))[0]
        self.position = tuple(value + (old if flags & (1 << index) else 0)
                              for index, (value, old) in enumerate(zip(coords, self.position)))
        self.rotation = tuple(value + (old if flags & (1 << (index + 3)) else 0)
                              for index, (value, old) in enumerate(zip(rotation, self.rotation)))
        self.send("accept_teleportation", packet_int(teleport_id) + struct.pack(">dddff", *self.position, *self.rotation))
        return True

    def wait(self, name, accept=lambda _: True):
        names = {entry["protocol_id"]: key.split(":")[1]
                 for key, entry in self.packets[self.phase]["clientbound"].items()}
        end = time.monotonic() + 20
        while time.monotonic() < end:
            packet = io.BytesIO(exact(self.stream, read_int(self.stream)))
            kind = names[read_int(packet)]
            data = packet.read()
            if kind in ("disconnect", "login_disconnect"):
                raise AssertionError(f"Probe client disconnected: {data!r}")
            if kind == "keep_alive":
                self.send("keep_alive", data)
            if kind == "ping":
                self.send("pong", data)
            if kind == "chunk_batch_finished":
                self.send("chunk_batch_received", struct.pack(">f", 64))
            if kind == "player_position" and not self.accept_position(data):
                continue
            if kind == name and accept(data):
                return data
        raise TimeoutError(f"Probe client waited for {name}")


class Fixture:
    def __init__(self, base, api, mod, java, classpath):
        self.base = base
        self.java = java
        self.classpath = classpath
        base.mkdir()
        mods = base / "mods"
        mods.mkdir()
        shutil.copyfile(api, mods / api.name)
        shutil.copyfile(mod, mods / mod.name)
        self.artifact_hash = hashlib.sha256(mod.read_bytes()).hexdigest()
        (base / "eula.txt").write_text("eula=true\n")
        self.secret = secrets.token_urlsafe(18)
        self.port = free_port()
        self.rcon = free_port()
        while self.rcon == self.port:
            self.rcon = free_port()
        (base / "server.properties").write_text(
            f"level-name=synthetic-v1\nlevel-type=minecraft:flat\nserver-ip=127.0.0.1\n"
            f"server-port={self.port}\nonline-mode=false\nenforce-secure-profile=false\n"
            f"network-compression-threshold=-1\nenable-rcon=true\nrcon.port={self.rcon}\n"
            f"rcon.password={self.secret}\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n"
            f"white-list=false\npause-when-empty-seconds=-1\n"
            f"generator-settings={{\"biome\":\"minecraft:plains\",\"layers\":[{{\"block\":\"minecraft:bedrock\",\"height\":1}},"
            f"{{\"block\":\"minecraft:dirt\",\"height\":2}},{{\"block\":\"minecraft:grass_block\",\"height\":1}}]}}\n"
        )

    def command(self, command):
        def send(sock, request, kind, payload):
            content = struct.pack("<ii", request, kind) + payload.encode() + b"\0\0"
            sock.sendall(struct.pack("<i", len(content)) + content)

        with socket.create_connection(("127.0.0.1", self.rcon), timeout=15) as sock, sock.makefile("rb") as stream:
            def receive():
                length = struct.unpack("<i", exact(stream, 4))[0]
                message = exact(stream, length)
                return struct.unpack_from("<i", message)[0], message[8:-2].decode()

            send(sock, 1, 3, self.secret)
            assert receive()[0] == 1, "Probe RCON auth failed"
            send(sock, 2, 2, command)
            response = receive()
            assert response[0] == 2, response
            return response[1]

    @contextlib.contextmanager
    def running(self, name):
        log = self.base / f"{name}.log"
        with log.open("x") as output:
            arguments = ["-Xmx2G", "-cp", self.classpath, "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
            argfile = self.base / "server.args"
            build.write_java_args(argfile, arguments)
            server = subprocess.Popen([str(self.java), "@" + str(argfile)], cwd=self.base,
                                      stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
            try:
                retry(lambda: server.poll() is None and "Active event boundaries" in self.command("eventboundary list"), 120)
                yield
            finally:
                if server.poll() is None:
                    try:
                        self.command("stop")
                        server.wait(45)
                    except (OSError, EOFError, subprocess.TimeoutExpired):
                        server.kill()
                        server.wait()
                assert server.returncode == 0, f"Fixture exited {server.returncode}. See {log}"

    def position(self, name):
        answer = self.command(f"data get entity {name} Pos")
        coordinates = re.search(r"\[([-+0-9.Ee]+)d, ([-+0-9.Ee]+)d, ([-+0-9.Ee]+)d\]", answer)
        assert coordinates, answer
        return tuple(map(float, coordinates.groups()))

    def expect_position(self, name, x, y=FLOOR_Y, z=.5):
        retry(lambda: all(abs(a - b) < 0.025 for a, b in zip(self.position(name), (x, y, z))), 5)

    def check(self, command, fragment):
        answer = self.command(command)
        assert fragment in answer, (command, answer)
        return answer

    def in_dimension(self, name, dimension):
        return "Test passed" in self.command(f"execute as {name} at @s if dimension {dimension}")

    def tp(self, client, name, x, y=FLOOR_Y, z=.5, dimension=None):
        prefix = f"execute in {dimension} run " if dimension else ""
        self.command(f"{prefix}tp {name} {x} {y} {z}")
        client.wait("player_position")
        if dimension:
            client.send("player_loaded")
        self.expect_position(name, x, y, z)
        time.sleep(.12)

    def create(self, id, mode, x=.5, y=FLOOR_Y, z=.5, radius=4, time_seconds=900, height=None):
        extent = f" height {height}" if height is not None else ""
        self.check(f"execute positioned {x} {y} {z} run eventboundary create {id} {mode} {radius} {time_seconds}{extent}",
                   f"Created event boundary {id}")


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def retry(condition, seconds=15):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if condition():
                return
        except (OSError, EOFError):
            pass
        time.sleep(.25)
    raise TimeoutError("Fixture condition was not met")


def connect(fixture, client, name):
    client.join(fixture.port, PROTOCOL, name)
    time.sleep(.18)


def move(client, x, y=FLOOR_Y, z=.5):
    client.send("move_player_pos", struct.pack(">dddB", x, y, z, 1))
    client.send("client_tick_end")
    time.sleep(.12)


def outside(fixture, name, radius=4):
    x, _, z = fixture.position(name)
    return math.hypot(x - .5, z - .5) > radius


def initial(fixture, packets):
    with fixture.running("initial"):
        with Client(fixture.port, packets) as runner, Client(fixture.port, packets) as guest:
            connect(fixture, runner, RUNNER)
            connect(fixture, guest, GUEST)
            fixture.tp(runner, RUNNER, 5.5)
            fixture.tp(guest, GUEST, .5)
            fixture.create("plaza", "entry")
            fixture.check("eventboundary freeze plaza", "Captured 1 online occupants")
            fixture.check("eventboundary members plaza roster", str(offline_id(GUEST)))

            runner.send("chat_command", string("eventboundary release plaza"))
            time.sleep(.20)
            fixture.check("eventboundary info plaza", "plaza")
            fixture.create("opcheck", "entry", x=45.5)
            fixture.check(f"op {RUNNER}", "Made")
            runner.send("chat_command", string("eventboundary release opcheck"))
            retry(lambda: "No active boundary" in fixture.command("eventboundary info opcheck"), 5)
            fixture.check(f"deop {RUNNER}", "no longer")
            move(runner, 4.0)
            runner.wait("player_position")
            fixture.expect_position(RUNNER, 5.5)
            # A swept movement chord crosses the perimeter despite ending on the starting side.
            fixture.tp(runner, RUNNER, -3.0, FLOOR_Y, 3.5)
            move(runner, 4.0, FLOOR_Y, 3.5)
            runner.wait("player_position")
            fixture.expect_position(RUNNER, -3.0, FLOOR_Y, 3.5)
            fixture.tp(runner, RUNNER, 5.5)
            fixture.command(f"tp {RUNNER} .5 {FLOOR_Y} .5")
            fixture.expect_position(RUNNER, 5.5)

            fixture.tp(guest, GUEST, 6.5)
            fixture.tp(guest, GUEST, .5)
            fixture.check(f"eventboundary pass plaza grant entry player {RUNNER}", str(offline_id(RUNNER)))
            # Relative teleport through the closed boundary succeeds under the explicit pass.
            fixture.command(f"execute as {RUNNER} at @s run tp @s ~-5 ~ ~")
            runner.wait("player_position")
            fixture.expect_position(RUNNER, .5)
            fixture.check(f"eventboundary pass plaza revoke entry uuid {offline_id(RUNNER)}", "Revoked")

            fixture.create("capture", "locked", radius=3)
            move(runner, 3.8)
            runner.wait("player_position")
            fixture.expect_position(RUNNER, .5)
            fixture.check(f"eventboundary pass capture grant exit player {RUNNER}", "Granted")
            fixture.tp(runner, RUNNER, 6.5)
            # A pass in one zone cannot override a denial in another.
            fixture.check(f"eventboundary pass capture grant entry player {RUNNER}", "Granted")
            fixture.command(f"tp {RUNNER} .5 {FLOOR_Y} .5")
            fixture.expect_position(RUNNER, 6.5)
            fixture.check("eventboundary release capture", "Released")

            fixture.create("room", "entry", x=12.5, height=1, radius=3)
            fixture.command(f"tp {RUNNER} 12.5 {FLOOR_Y} .5")
            fixture.expect_position(RUNNER, 6.5)
            fixture.tp(runner, RUNNER, 12.5, FLOOR_Y + 4)
            fixture.tp(runner, RUNNER, 6.5)
            fixture.check("eventboundary release room", "Released")

            fixture.create("quick", "exit", x=40.5, time_seconds=1)
            time.sleep(1.15)
            fixture.check("eventboundary info quick", "No active boundary")
            fixture.check("eventboundary list", "Active event boundaries 1")

            # Leave the second player (the frozen occupant) and a temporary authorized guest inside.
            fixture.check(f"eventboundary pass plaza grant entry player {RUNNER}", "Granted")
            fixture.tp(runner, RUNNER, .5)
            fixture.check(f"eventboundary pass plaza revoke entry player {RUNNER}", "Revoked")
        retry(lambda: "0 of a max" in fixture.command("list"), 12)


def resumed(fixture, packets):
    with fixture.running("restart"):
        fixture.check("eventboundary info plaza", "Frozen roster 1")
        with Client(fixture.port, packets) as runner, Client(fixture.port, packets) as guest:
            connect(fixture, runner, RUNNER)
            connect(fixture, guest, GUEST)
            retry(lambda: outside(fixture, RUNNER), 5)
            runner.wait("player_position")
            fixture.expect_position(GUEST, .5)
            fixture.create("hold", "exit", radius=2)
            fixture.command(f"execute in minecraft:the_nether run tp {GUEST} .5 150.0 .5")
            fixture.expect_position(GUEST, .5)
            fixture.check(f"eventboundary pass hold grant exit player {GUEST}", "Granted")
            fixture.tp(guest, GUEST, .5, 150.0, dimension="minecraft:the_nether")
            fixture.check(f"eventboundary pass hold revoke exit player {GUEST}", "Revoked")
            fixture.tp(guest, GUEST, .5, dimension="minecraft:overworld")

            # A vanilla respawn can safely leave a hold-in. An unauthorized respawn into keep-out is corrected.
            fixture.command(f"spawnpoint {GUEST} 7 {int(FLOOR_Y)} 0")
            fixture.command(f"kill {GUEST}")
            guest.send("client_command", b"\0")
            guest.wait("player_position")
            retry(lambda: fixture.position(GUEST)[0] > 4.5, 5)
            fixture.check("eventboundary release hold", "Released")
            fixture.command(f"spawnpoint {RUNNER} 0 {int(FLOOR_Y)} 0")
            fixture.command(f"kill {RUNNER}")
            runner.send("client_command", b"\0")
            runner.wait("player_position")
            retry(lambda: outside(fixture, RUNNER), 5)
            runner.wait("player_position")
            runner.send("player_loaded")


def end_credits(fixture, packets):
    with fixture.running("end-credits"):
        with Client(fixture.port, packets) as runner:
            connect(fixture, runner, RUNNER)
            end = "minecraft:the_end"
            prefix = f"execute in {end} run "
            fixture.tp(runner, RUNNER, .5, 150.0, dimension=end)
            fixture.check(prefix + "setblock 0 149 0 minecraft:stone", "Changed the block")
            fixture.check(f"execute in {end} positioned .5 150.0 .5 run eventboundary create credits exit 8 180",
                          "Created event boundary credits")
            fixture.check(prefix + "setblock 0 150 0 minecraft:end_portal", "Changed the block")
            runner.wait("system_chat", lambda payload: b"Exit is closed for event boundary credits" in payload)
            assert fixture.in_dimension(RUNNER, end)
            assert "credits" in fixture.command("eventboundary info credits")
            fixture.check(f"eventboundary pass credits grant exit player {RUNNER}", "Granted")
            runner.wait("game_event", lambda payload: payload[:1] == b"\x04")
            runner.send("client_command", b"\0")
            runner.wait("player_position")
            retry(lambda: fixture.in_dimension(RUNNER, "minecraft:overworld"), 5)


def vehicles(fixture, packets):
    with fixture.running("vehicles"):
        with Client(fixture.port, packets) as runner, Client(fixture.port, packets) as guest:
            connect(fixture, runner, RUNNER)
            connect(fixture, guest, GUEST)
            fixture.tp(runner, RUNNER, 6.5)
            fixture.tp(guest, GUEST, 6.5)
            fixture.create("vehicle", "entry")
            boat = "@e[type=minecraft:oak_boat,limit=1]"
            fixture.check(f"summon minecraft:oak_boat 6.5 {FLOOR_Y} .5", "Summoned")
            fixture.check(f"ride {RUNNER} mount {boat}", "riding")
            runner.wait("player_position")
            fixture.check(f"ride {GUEST} mount {boat}", "riding")
            guest.wait("player_position")
            time.sleep(.35)
            fixture.expect_position(boat, 6.5)
            fixture.check(f"eventboundary pass vehicle grant entry player {RUNNER}", "Granted")

            # A non-passed passenger blocks a passed driver using either a vehicle packet or an entity teleport.
            fixture.command(f"tp {boat} 3.5 {FLOOR_Y} .5")
            fixture.expect_position(boat, 6.5)
            payload = struct.pack(">dddff?", 3.5, FLOOR_Y, .5, 0, 0, True)
            runner.send("move_vehicle", payload)
            runner.send("client_tick_end")
            time.sleep(.3)
            fixture.expect_position(boat, 6.5)
            fixture.check(f"eventboundary pass vehicle grant entry player {GUEST}", "Granted")
            runner.send("move_vehicle", payload)
            runner.send("client_tick_end")
            fixture.expect_position(boat, 3.5)
            assert not outside(fixture, RUNNER) and not outside(fixture, GUEST)


def read_packet_report(java, game, base):
    # The protocol report is generated by the pinned game in the disposable test directory.
    argfile = base / "reports.args"
    build.write_java_args(argfile, ["-cp", os.pathsep.join(map(str, game)), "net.minecraft.data.Main", "--reports"])
    subprocess.run([str(java), "@" + str(argfile)], cwd=base, check=True)
    return json.loads((base / "generated/reports/packets.json").read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True, help="New directory for the disposable server tests")
    parser.add_argument("--cache", type=Path, default=build.PROJECT / "local/deps")
    parser.add_argument("--mod", type=Path, default=build.PROJECT / "build/libs/eventboundaries-0.1.0.jar")
    parser.add_argument("--accept-eula", action="store_true",
                        help="Indicate your acceptance of https://www.minecraft.net/en-us/eula for this test server")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read the Minecraft EULA and pass --accept-eula if you agree.")
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    mod = args.mod.resolve(strict=True)
    cache = args.cache.resolve()
    game = build.validate_runtime(build.prepare_runtime(cache))
    artifacts = build.pinned_artifacts(cache)
    classpath = os.pathsep.join(map(str, [*game, *(path for path in artifacts if path.name != "api.jar")]))
    base = args.fixture.resolve()
    base.mkdir()
    snapshot = base / mod.name
    shutil.copyfile(mod, snapshot)
    with zipfile.ZipFile(snapshot) as artifact:
        metadata = json.loads(artifact.read("fabric.mod.json"))
        if metadata["id"] != "eventboundaries" or metadata["version"] != "0.1.0":
            raise ValueError("This fixture expects Event Boundaries 0.1.0")
    packets = read_packet_report(java, game, base)
    for name, checks in (
        ("movement", (initial, resumed)),
        ("vehicles", (vehicles,)),
        ("credits", (end_credits,)),
    ):
        fixture = Fixture(base / name, cache / "api.jar", snapshot, java, classpath)
        for check in checks:
            check(fixture, packets)
        print(f"PASS {name} fixture, JAR SHA-256 {fixture.artifact_hash}", flush=True)
    print(f"PASS dedicated 26.3 Event Boundaries JAR SHA-256 {fixture.artifact_hash}", flush=True)


if __name__ == "__main__":
    main()
