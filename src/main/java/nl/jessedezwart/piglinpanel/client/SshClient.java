package nl.jessedezwart.piglinpanel.client;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.model.SshConfig;

public class SshClient implements AutoCloseable {
  private final Session session;

  public SshClient(ServerProfile profile) throws IOException {
    try {
      SshConfig config = profile.getSsh();
      JSch jsch = new JSch();
      if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isBlank()) {
        if (config.getPrivateKeyPassphrase() == null
            || config.getPrivateKeyPassphrase().isBlank()) {
          jsch.addIdentity(config.getPrivateKeyPath());
        } else {
          jsch.addIdentity(config.getPrivateKeyPath(), config.getPrivateKeyPassphrase());
        }
      }
      session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
      if (config.getPassword() != null && !config.getPassword().isBlank()) {
        session.setPassword(config.getPassword());
      }
      Properties properties = new Properties();
      properties.put("StrictHostKeyChecking", "no");
      session.setConfig(properties);
      session.connect((int) Duration.ofSeconds(15).toMillis());
    } catch (JSchException e) {
      throw new IOException("Could not connect over SSH", e);
    }
  }

  public CommandResult exec(String command) throws IOException {
    RawCommandResult raw = execRaw(command);
    return new CommandResult(
        raw.exitCode(),
        raw.stdout().toString(StandardCharsets.UTF_8),
        raw.stderr().toString(StandardCharsets.UTF_8));
  }

  public RawCommandResult execRaw(String command) throws IOException {
    try {
      ChannelExec channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand(command);
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      channel.setOutputStream(stdout);
      channel.setErrStream(stderr);
      channel.connect();
      while (!channel.isClosed()) {
        Thread.sleep(20);
      }
      int exit = channel.getExitStatus();
      channel.disconnect();
      return new RawCommandResult(exit, stdout, stderr);
    } catch (JSchException e) {
      throw new IOException("SSH command failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("SSH command interrupted", e);
    }
  }

  public void writeToCommand(String command, byte[] bytes) throws IOException {
    try {
      ChannelExec channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand(command);
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      channel.setErrStream(stderr);
      channel.connect();
      try (var input = channel.getOutputStream()) {
        input.write(bytes);
      }
      while (!channel.isClosed()) {
        Thread.sleep(20);
      }
      int exit = channel.getExitStatus();
      channel.disconnect();
      if (exit != 0) {
        throw new IOException(stderr.toString(StandardCharsets.UTF_8));
      }
    } catch (JSchException e) {
      throw new IOException("SSH write command failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("SSH write command interrupted", e);
    }
  }

  @Override
  public void close() {
    if (session != null && session.isConnected()) {
      session.disconnect();
    }
  }

  public record CommandResult(int exitCode, String stdout, String stderr) {
    public void throwIfFailed() throws IOException {
      if (exitCode != 0) {
        throw new IOException(
            stderr.isBlank() ? "Remote command failed with exit " + exitCode : stderr);
      }
    }
  }

  public record RawCommandResult(
      int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
    public void throwIfFailed() throws IOException {
      if (exitCode != 0) {
        String error = stderr.toString(StandardCharsets.UTF_8);
        throw new IOException(
            error.isBlank() ? "Remote command failed with exit " + exitCode : error);
      }
    }
  }
}
