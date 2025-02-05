/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023 */
package ua.com.pragmasoft.k1te.backend.router.domain;

import java.time.Instant;
import ua.com.pragmasoft.k1te.backend.router.domain.payload.*;

public interface HistoryMessage {

  String getChannelName();

  String getMemberId();

  String getMessageId();

  String getContent();

  Instant getTime();
}
