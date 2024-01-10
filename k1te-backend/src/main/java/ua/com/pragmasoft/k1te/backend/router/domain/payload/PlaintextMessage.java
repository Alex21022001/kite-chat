/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023-2024 */
package ua.com.pragmasoft.k1te.backend.router.domain.payload;

import java.time.Instant;
import java.util.Objects;

public record PlaintextMessage(String text, String messageId, Instant created, Integer status)
    implements MessagePayload {

  public PlaintextMessage(String text, String messageId, Instant created, Integer status) {
    Objects.requireNonNull(text, "text");
    this.text = text;
    Objects.requireNonNull(messageId, "messageId");
    this.messageId = messageId;
    this.created = created;
    this.status = status;
  }

  public PlaintextMessage(String text, String messageId, Instant created) {
    this(text, messageId, created, 0);
  }

  public PlaintextMessage(String text, String messageId) {
    this(text, messageId, Instant.now());
  }

  public PlaintextMessage(String text) {
    this(text, "-");
  }

  @Override
  public Type type() {
    return Type.TXT;
  }

  @Override
  public String toString() {
    return type().label
        + " [text="
        + text
        + ", messageId="
        + messageId
        + ", created="
        + created
        + "]";
  }
}
