package nl.jessedezwart.piglinpanel.client;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import nl.jessedezwart.piglinpanel.model.RconConfig;

public class RconClient implements Closeable {
  private static final int AUTH = 3;
  private static final int COMMAND = 2;
  private static final int RESPONSE = 0;
  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final AtomicInteger requestId = new AtomicInteger(100);

  public RconClient(RconConfig config) throws IOException {
    socket = new Socket(config.getHost(), config.getPort());
    input = new DataInputStream(socket.getInputStream());
    output = new DataOutputStream(socket.getOutputStream());
    int id = requestId.incrementAndGet();
    send(id, AUTH, config.getPassword());
    Packet response = read();
    if (response.id() == -1 || response.id() != id) {
      throw new IOException("RCON authentication failed");
    }
  }

  public String command(String command) throws IOException {
    int id = requestId.incrementAndGet();
    send(id, COMMAND, command);
    Packet response = read();
    if (response.type() != RESPONSE && response.type() != COMMAND) {
      throw new IOException("Unexpected RCON response type " + response.type());
    }
    return response.body();
  }

  private void send(int id, int type, String body) throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer =
        ByteBuffer.allocate(4 + 4 + bodyBytes.length + 2).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(id);
    buffer.putInt(type);
    buffer.put(bodyBytes);
    buffer.put((byte) 0);
    buffer.put((byte) 0);
    output.writeInt(Integer.reverseBytes(buffer.capacity()));
    output.write(buffer.array());
    output.flush();
  }

  private Packet read() throws IOException {
    int length = Integer.reverseBytes(input.readInt());
    byte[] bytes = input.readNBytes(length);
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int id = buffer.getInt();
    int type = buffer.getInt();
    byte[] body = new byte[Math.max(0, length - 10)];
    buffer.get(body);
    return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private record Packet(int id, int type, String body) {}
}
