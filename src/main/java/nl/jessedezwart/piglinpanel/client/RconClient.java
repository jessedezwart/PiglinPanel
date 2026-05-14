package nl.jessedezwart.piglinpanel.client;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import nl.jessedezwart.piglinpanel.model.RconConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RconClient implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(RconClient.class);
  private static final int AUTH = 3;
  private static final int COMMAND = 2;
  private static final int RESPONSE = 0;
  private static final Charset PAYLOAD_CHARSET = StandardCharsets.US_ASCII;
  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final AtomicInteger requestId = new AtomicInteger(100);

  public RconClient(RconConfig config) throws IOException {
    log.info("Connecting to RCON at {}:{}", config.getHost(), config.getPort());
    socket = new Socket(config.getHost(), config.getPort());
    input = new DataInputStream(socket.getInputStream());
    output = new DataOutputStream(socket.getOutputStream());
    int id = requestId.incrementAndGet();
    send(id, AUTH, config.getPassword());
    Packet response = readForId(id);
    if (response.id() == -1 || response.id() != id) {
      throw new IOException("RCON authentication failed");
    }
    log.info("RCON authentication succeeded for {}:{}", config.getHost(), config.getPort());
  }

  public String command(String command) throws IOException {
    log.info("Sending RCON command");
    int id = requestId.incrementAndGet();
    send(id, COMMAND, command);
    Packet response = readForId(id);
    if (response.type() != RESPONSE && response.type() != COMMAND) {
      throw new IOException("Unexpected RCON response type " + response.type());
    }
    return response.body();
  }

  private void send(int id, int type, String body) throws IOException {
    byte[] bodyBytes = body.getBytes(PAYLOAD_CHARSET);
    int bodyLength = 4 + 4 + bodyBytes.length + 2;
    ByteBuffer buffer = ByteBuffer.allocate(4 + bodyLength).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(bodyLength);
    buffer.putInt(id);
    buffer.putInt(type);
    buffer.put(bodyBytes);
    buffer.put((byte) 0);
    buffer.put((byte) 0);
    output.write(buffer.array());
    output.flush();
  }

  private Packet read() throws IOException {
    try {
      int length = Integer.reverseBytes(input.readInt());
      if (length < 10) {
        throw new IOException("Malformed RCON packet length " + length);
      }
      byte[] bytes = new byte[length];
      input.readFully(bytes);
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      int id = buffer.getInt();
      int type = buffer.getInt();
      byte[] body = new byte[length - 10];
      buffer.get(body);
      byte null1 = buffer.get();
      byte null2 = buffer.get();
      if (null1 != 0 || null2 != 0) {
        throw new IOException("Invalid RCON packet terminator");
      }
      return new Packet(id, type, new String(body, PAYLOAD_CHARSET));
    } catch (EOFException e) {
      throw new EOFException("RCON connection closed by server");
    }
  }

  private Packet readForId(int expectedId) throws IOException {
    while (true) {
      Packet packet = read();
      if (packet.id() == expectedId) {
        return packet;
      }
      log.debug("Skipping RCON packet with id {}", packet.id());
    }
  }

  @Override
  public void close() throws IOException {
    log.info("Closing RCON connection");
    socket.close();
  }

  private record Packet(int id, int type, String body) {
  }
}
