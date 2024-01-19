/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023-2024 */
package ua.com.pragmasoft.k1te.backend.tg;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.ChatMember;
import com.pengrad.telegrambot.model.ChatMember.Status;
import com.pengrad.telegrambot.model.File;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.MessageEntity;
import com.pengrad.telegrambot.model.MessageEntity.Type;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.GetFileResponse;
import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.com.pragmasoft.k1te.backend.router.domain.*;
import ua.com.pragmasoft.k1te.backend.router.domain.payload.*;
import ua.com.pragmasoft.k1te.backend.shared.NotFoundException;
import ua.com.pragmasoft.k1te.backend.shared.RoutingException;
import ua.com.pragmasoft.k1te.backend.shared.ValidationException;
import ua.com.pragmasoft.k1te.backend.ws.PayloadDecoder;

public class TelegramConnector implements Connector, Closeable {

  private static final Logger log = LoggerFactory.getLogger(TelegramConnector.class);
  private static final String[] ALLOWED_UPDATES =
      new String[] {"message", "edited_message", "chat_member"};
  private static final PayloadDecoder DECODER = new PayloadDecoder();
  private static final boolean PIN_FEATURE_FLAG = true;

  private static final Integer HISTORY_LIMIT = 10;
  private static final String UNSUPPORTED_PAYLOAD = "Unsupported payload ";
  public static final String TG = "tg";
  private static final String OK = "ok";
  private static final String SUCCESS = "✅ ";
  private static final String FAIL = "⛔ ";
  private static final String HELP =
      """
      This bot allows to set up support channel in the current chat as a host
      or call existing support channel as a client.

      /host *channel* set up current chat as a support channel named *channel*
      /drop unregister current support channel

      /join *channel* start conversation with support channel named *channel*
      /leave leave current support channel

      /info show the information about your current Channel
      *channel* name should contain only alphanumeric letters, -(minus), \\_(underline)
      and be 8..32 characters long.

      Once conversation is established, bot will forward messages from client to host and vice versa.

      Host messages will be forwarded to the client who sent the last incoming message.

      Use ↰ (Reply To) to respond to other messages.
      """;
  private static final String ANONYMOUS_INFO =
      """
      You don't have any channels at the moment.
      To join one, use /join channelName.
      For more information about possible actions, use /help.
      """;
  private static final String INFO =
      """
      Hello %s!

      You are a %s of the %s channel.

      As a %s, you have the following privileges:
      - Manage channel settings
      - Moderate discussions and activities
      If you need any further information or assistance use /help.
      """;

  private final TelegramBot bot;
  private final Router router;
  private final Channels channels;
  private final Messages messages;
  private final URI base;
  private final URI wsApi;
  private final String secretToken;

  public TelegramConnector(
      final TelegramBot bot,
      final Router router,
      final Channels channels,
      final Messages messages,
      final URI base,
      URI wsApi,
      String secretToken) {
    this.bot = bot;
    this.router = router;
    this.router.registerConnector(this);
    this.channels = channels;
    this.messages = messages;
    this.base = base;
    this.secretToken = secretToken;
    if (wsApi.getScheme().equals("wss")) {
      this.wsApi = wsApi;
    } else {
      try {
        this.wsApi = new URI("wss", wsApi.getSchemeSpecificPart(), wsApi.getFragment());
      } catch (URISyntaxException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }
    log.info("Base {}, wsApi {}", this.base, this.wsApi);
  }

  public URI setWebhook() {
    log.debug("Register telegram webhook {}", this.base);
    var request =
        new SetWebhook()
            .url(this.base.toASCIIString())
            .allowedUpdates(ALLOWED_UPDATES)
            .secretToken(secretToken);
    var response = this.bot.execute(request);
    if (log.isDebugEnabled()) {
      log.debug(response.toString());
    }
    if (!response.isOk()) {
      throw new IllegalStateException(response.description());
    }
    return this.base;
  }

  public void close() {
    log.info("close");
    try {
      this.bot.execute(new DeleteWebhook());
    } finally {
      this.bot.shutdown();
    }
  }

  public String onUpdate(final Update u) {
    var message = u.message();
    var isEdited = false;
    if (null == message) message = u.channelPost();
    if (null == message) {
      message = u.editedMessage();
      isEdited = true;
    }
    if (null == message) {
      message = u.editedChannelPost();
    }
    if (isBotMember(u)) {
      log.debug("Bot {} was added to the Group", parseBotName(u));
      return new SendMessage(
              u.myChatMember().chat().id(), SUCCESS + "You successfully added " + parseBotName(u))
          .toWebhookResponse();
    }
    if (isBotAdmin(u)) {
      log.debug("Bot has been made an administrator");
      new SendMessage(u.myChatMember().chat().id(), SUCCESS + "Bot is an Administrator now");
      return OK;
    }
    if (isBotLeft(u)) {
      try {
        return onBotLeft(u);
      } catch (NotFoundException e) {
        log.debug("Bot left the Group, but there were no Channels assigned to this Group");
        return OK;
      }
    }
    if (null == message) return this.onUnhandledUpdate(u);
    try {
      if (isCommand(message)) {
        return this.onCommand(message);
      } else if (message.groupChatCreated() != null && message.groupChatCreated()) {
        log.debug("GroupChat {} was created with Bot", message.chat().title());
        return OK;
      } else if (isNewChatMember(message)) {
        log.debug(
            "{} members were added to Group {}",
            message.newChatMembers().length,
            message.chat().title());
        return OK;
      } else if (isMemberLeft(message)) {
        log.debug(
            "Member {} has left the Group {}",
            message.leftChatMember().username(),
            message.chat().title());
        return OK;
      } else if (isPinnedMessage(message)) {
        bot.execute(
            new DeleteMessage(
                message.chat().id(),
                message.messageId())); // Delete notification that message was pinned
        return OK;
      } else {
        return this.onMessage(message, isEdited);
      }
    } catch (Exception e) {
      log.error("onUpdate", e);
      return new SendMessage(message.chat().id(), "⛔ " + e.getMessage()).toWebhookResponse();
    }
  }

  @Override
  public String id() {
    return TG;
  }

  @Override
  public void dispatch(RoutingContext ctx) {
    Long destinationChatId = toLong(Connector.rawConnection(ctx.destinationConnection));
    Member from = ctx.from;
    Member to = ctx.to;
    AbstractSendRequest<?> sendMessage;
    if (ctx.request instanceof PlaintextMessage plaintext) {
      String text = plaintext.text();
      if (to.isHost()) {
        text = '#' + from.getId() + " " + from.getUserName() + '\n' + text;
      }
      sendMessage = new SendMessage(destinationChatId, text);
    } else if (ctx.request instanceof BinaryPayload binaryPayload) {

      var fileIdOrUri =
          (binaryPayload instanceof TelegramBinaryMessage telegramBinaryPayload)
              ? telegramBinaryPayload.fileId()
              : binaryPayload.uri().toString();

      var binaryMessage =
          binaryPayload.isImage()
                  && !binaryPayload.fileType().equals("image/gif")
                  && !binaryPayload.fileType().equals("image/webp")
              ? new SendPhoto(destinationChatId, fileIdOrUri)
              : new SendDocument(destinationChatId, fileIdOrUri);

      sendMessage =
          binaryMessage.fileName(binaryPayload.fileName()).contentType(binaryPayload.fileType());

    } else {
      throw new RoutingException(UNSUPPORTED_PAYLOAD + ctx.request.getClass().getSimpleName());
    }
    if (log.isDebugEnabled()) {
      log.debug(">> {}", sendMessage.toWebhookResponse());
    }
    var sendResponse = this.bot.execute(sendMessage);
    if (log.isDebugEnabled()) {
      log.debug("<< {}", sendResponse);
    }
    if (!sendResponse.isOk()) {
      throw new RoutingException(
          "%s connector error: (%d) %s"
              .formatted(this.id(), sendResponse.errorCode(), sendResponse.description()));
    }

    if (PIN_FEATURE_FLAG && from != to) {
      String text = sendResponse.message().text();
      boolean isJoinMessage =
          text != null && text.contains(SUCCESS) && text.contains("joined channel");
      boolean isLeaveMessage =
          text != null && text.contains(SUCCESS) && text.contains("left channel");
      boolean isSwitchMessage =
          text != null && text.contains(SUCCESS) && text.contains("switched to Telegram");

      String pinnedMessageId = this.channels.findUnAnsweredMessage(from, to);
      if (pinnedMessageId == null) {
        if (!isJoinMessage && !isLeaveMessage && !isSwitchMessage) {
          PinChatMessage pinChatMessage =
              new PinChatMessage(destinationChatId, sendResponse.message().messageId())
                  .disableNotification(true);
          bot.execute(pinChatMessage);
          from.updateUnAnsweredMessage(
              to, fromLong(sendResponse.message().messageId().longValue()));
          log.debug(
              "Member {} pinned message {}", from.getId(), sendResponse.message().messageId());
        }
      } else {
        if (isLeaveMessage) {
          UnpinChatMessage unpinChatMessage =
              new UnpinChatMessage(destinationChatId).messageId(toLong(pinnedMessageId).intValue());
          bot.execute(unpinChatMessage);
          from.deleteUnAnsweredMessage(to);
          log.debug(
              "Member {} left the Channel, his pinnedMessage {} was deleted",
              from.getId(),
              pinnedMessageId);
        }
      }
    }

    ctx.response =
        new MessageAck(
            ctx.request.messageId(),
            fromLong(sendResponse.message().messageId().longValue()),
            Instant.ofEpochSecond(sendResponse.message().date()));
  }

  private String onCommand(final Message message) {
    CommandWithArgs cmd = parseCommand(message);
    String command = cmd.command;
    Long rawChatId = message.chat().id();
    if ("/help".equals(command)) {
      return new SendMessage(rawChatId, HELP).parseMode(ParseMode.Markdown).toWebhookResponse();
    }
    String memberId = fromLong(rawChatId);
    String originConnection = this.connectionUri(memberId);
    String response;

    if ("/info".equals(command)) {
      return onInfoCommand(rawChatId, originConnection);
    }
    if ("/start".equals(command)) {
      if (cmd.args.isEmpty())
        return new SendMessage(rawChatId, HELP).parseMode(ParseMode.Markdown).toWebhookResponse();

      String memberName = userToString(message.from());

      if (cmd.hasSubCommand()) {
        SubCommand subCommand = cmd.subCommand();
        String channelName = subCommand.args[0];

        response =
            switch (subCommand.type) {
              case HOST ->
                  onHostCommand(channelName, message.chat().title(), memberId, originConnection);
              case JOIN -> {
                if (subCommand.args.length > 1) { // /join channelName userId
                  String userId = subCommand.args[1];
                  yield onSwitchConnection(rawChatId, channelName, userId, originConnection);
                }
                yield onStartCommand(channelName, memberId, originConnection, memberName);
              }
              default ->
                  throw new ValidationException("Unsupported subCommand type " + subCommand.type);
            };
      } else {
        String channelName = cmd.args;
        response = onStartCommand(channelName, memberId, originConnection, memberName);
      }
    } else if ("/join".equals(command)) {
      String channelName = cmd.args;
      String memberName = userToString(message.from());
      response = onStartCommand(channelName, memberId, originConnection, memberName);
    } else if ("/host".equals(command)) {
      String channelName = cmd.args;
      String title = message.chat().title();
      response = onHostCommand(channelName, title, memberId, originConnection);
    } else if ("/leave".equals(command)) {
      Member client = this.channels.leaveChannel(originConnection);
      this.router.dispatch(
          RoutingContext.create()
              .withOriginConnection(originConnection)
              .withFrom(client)
              .withRequest(
                  new PlaintextMessage(
                      "✅ %s left channel %s"
                          .formatted(client.getUserName(), client.getChannelName()))));
      response = "✅ You left channel %s".formatted(client.getChannelName());

    } else if ("/drop".equals(command)) {
      Member client = this.channels.dropChannel(originConnection);
      response = "✅ You dropped channel %s".formatted(client.getChannelName());

    } else {
      throw new ValidationException("Unsupported command " + command);
    }
    return new SendMessage(rawChatId, response).toWebhookResponse();
  }

  private String onInfoCommand(Long rawChatId, String originConnection) {
    try {
      Member member = channels.find(originConnection);
      String memberType = member.isHost() ? "Host" : "Member";
      String text =
          INFO.formatted(member.getUserName(), memberType, member.getChannelName(), memberType);

      return new SendMessage(rawChatId, text).parseMode(ParseMode.Markdown).toWebhookResponse();
    } catch (Exception e) {
      return new SendMessage(rawChatId, ANONYMOUS_INFO)
          .parseMode(ParseMode.Markdown)
          .toWebhookResponse();
    }
  }

  private String onHostCommand(
      String channelName, String title, String memberId, String originConnection) {
    this.channels.hostChannel(channelName, memberId, originConnection, title);
    String channelPublicUrl =
        this.wsApi.toString() + "?c=" + URLEncoder.encode(channelName, StandardCharsets.UTF_8);
    return "✅ Created channel %s. Use URL %s to configure k1te chat frontend"
        .formatted(channelName, channelPublicUrl);
  }

  private String onStartCommand(
      String channelName, String memberId, String originConnection, String memberName) {
    Member client = this.channels.joinChannel(channelName, memberId, originConnection, memberName);
    var ctx =
        RoutingContext.create()
            .withOriginConnection(originConnection)
            .withFrom(client)
            .withRequest(
                new PlaintextMessage("✅ %s joined channel %s".formatted(memberName, channelName)));
    this.router.dispatch(ctx);
    return "✅ You joined channel %s".formatted(channelName);
  }

  private String onSwitchConnection(
      Long chatId, String channelName, String memberId, String newConnection) {
    Member member = this.channels.switchConnection(channelName, memberId, newConnection);

    var ctx =
        RoutingContext.create()
            .withOriginConnection(newConnection)
            .withFrom(member)
            .withRequest(
                new PlaintextMessage("✅ %s switched to Telegram".formatted(member.getUserName())));
    this.router.dispatch(ctx);

    Messages.MessagesRequest messagesRequest =
        Messages.MessagesRequest.builder().member(member).limit(HISTORY_LIMIT).build();
    this.messages
        .findAll(messagesRequest)
        .forEach(message -> processHistoryMessage(chatId, newConnection, member, message));

    return "✅ You switched to Telegram";
  }

  private void processHistoryMessage(
      Long chatId, String newConnection, Member member, HistoryMessage message) {
    Payload payload = DECODER.apply(message.getContent());
    int status =
        payload.type() == Payload.Type.BIN
            ? ((BinaryPayload) payload).status()
            : ((PlaintextMessage) payload).status();
    boolean isIncoming = status == 0;

    if (payload.type() == Payload.Type.BIN && isIncoming) {
      Long fromChatId = toLong(member.getPeerMemberId());
      Integer messageId = toLong(message.getMessageId()).intValue();
      copyMessageTo(chatId, fromChatId, messageId, "#Host");
    } else {
      if (payload.type() == Payload.Type.TXT && isIncoming) {
        PlaintextMessage textMessage = (PlaintextMessage) payload;
        payload =
            new PlaintextMessage(
                "#Host \n" + textMessage.text(), textMessage.messageId(), textMessage.created());
      }
      var context =
          RoutingContext.create()
              .withOriginConnection(newConnection)
              .withFrom(member)
              .withTo(member)
              .withDestinationConnection(member.getConnectionUri())
              .withRequest((MessagePayload) payload);

      try {
        this.dispatch(context);
      } catch (RoutingException e) {
        log.warn("Couldn't send message {}", payload);
        this.bot.execute(new SendMessage(chatId, FAIL + "Unable to recover this message"));
      }
    }
  }

  private void copyMessageTo(Long toChatId, Long fromChatId, Integer messageId, String caption) {
    CopyMessage copyMessage =
        new CopyMessage(toChatId, fromChatId, messageId).disableNotification(true).caption(caption);

    this.bot.execute(copyMessage);
  }

  private String onMessage(final Message message, boolean isEdited) {
    Long rawChatId = message.chat().id();
    String originConnection = this.connectionUri(fromLong(rawChatId));
    Member from = this.channels.find(originConnection);
    final String toMemberId =
        Optional.ofNullable(message.replyToMessage())
            .flatMap(TelegramConnector::memberIdFromHashTag)
            .or(() -> Optional.ofNullable(from.getPeerMemberId()))
            .orElseThrow(RoutingException::new);
    Member to = this.channels.find(from.getChannelName(), toMemberId);
    String msgId = fromLong(message.messageId().longValue());
    Instant messageTimestamp = Instant.ofEpochSecond(message.date());
    int status = from.isHost() ? 0 : 2;
    MessagePayload request = null;
    var document = message.document();
    if (null != document) {
      request =
          new TelegramBinaryMessage(
              msgId,
              document.fileId(),
              document.fileName(),
              document.mimeType(),
              document.fileSize(),
              messageTimestamp,
              status);
    } else if (null != message.photo() && message.photo().length > 0) {
      PhotoSize photo = largestPhoto(message.photo());
      var photoFileName =
          Optional.ofNullable(message.caption()).orElse(ContentTypes.PHOTO_FILE_NAME);
      request =
          new TelegramBinaryMessage(
              msgId,
              photo.fileId(),
              photoFileName,
              ContentTypes.PHOTO_MIME_TYPE,
              photo.fileSize(),
              messageTimestamp,
              status);
    } else if (null != message.text()) {
      request = new PlaintextMessage(message.text(), msgId, messageTimestamp, status);
    } else {
      throw new RoutingException("unsupported message type");
    }

    var ctx =
        RoutingContext.create()
            .withOriginConnection(originConnection)
            .withFrom(from)
            .withTo(to)
            .withRequest(request);
    this.router.dispatch(ctx);

    if (PIN_FEATURE_FLAG) {
      String pinnedMessageId = channels.findUnAnsweredMessage(to, from);
      if (pinnedMessageId != null) {
        UnpinChatMessage unpinChatMessage =
            new UnpinChatMessage(rawChatId).messageId(toLong(pinnedMessageId).intValue());
        bot.execute(unpinChatMessage);
        to.deleteUnAnsweredMessage(from);
        log.debug("Member {} unpinned Message {}", to.getId(), pinnedMessageId);
      }
    }

    MessageAck ack = ctx.response;
    log.debug("Message #{} delivered", ack.messageId());
    return OK;
  }

  private String onBotLeft(Update update) {
    Long rawChatId = update.myChatMember().chat().id();
    String memberId = fromLong(rawChatId);
    String connectionUri = this.connectionUri(memberId);
    channels.dropChannel(connectionUri);

    log.debug("Bot has left the Group");
    return OK;
  }

  private String onUnhandledUpdate(final Update u) {
    log.warn("Unhandled update {}", u);
    return OK;
  }

  private static boolean isCommand(final Message message) {
    if (null == message) return false;
    final var entities = message.entities();
    if (null != entities && entities.length > 0) {
      final MessageEntity entity = entities[0];
      return Type.bot_command == entity.type();
    }
    return false;
  }

  /** Returns true if bot was not in the Chat/Group before and was added to one. */
  private static boolean isBotMember(Update update) {
    return whoIsBot(update, Status.left, Status.member);
  }

  private static boolean isBotAdmin(Update update) {
    return whoIsBot(update, Status.member, Status.administrator);
  }

  /**
   * Returns true if Bot has left the Chat/Group because the Group/Chat was deleted or Bot was
   * removed from it.
   */
  private static boolean isBotLeft(Update update) {
    return whoIsBot(update, Status.member, Status.left)
        || whoIsBot(update, Status.administrator, Status.left);
  }

  private static boolean whoIsBot(Update update, Status oldStatus, Status newStatus) {
    if (update.myChatMember() == null) return false;

    ChatMember newChatMember = update.myChatMember().newChatMember();
    ChatMember oldChatMember = update.myChatMember().oldChatMember();
    return oldChatMember.status() == oldStatus && newChatMember.status() == newStatus;
  }

  /**
   * Returns true if Member or Members were added to the Chat/Group. Be-careful- also returns true
   * if Bot was added to the Chat/Group. Should not use alongside isBotMember().
   */
  private static boolean isNewChatMember(Message message) {
    return message.newChatMembers() != null && message.newChatMembers().length > 0;
  }

  /**
   * Returns true when User or Bot has left the Chat/Group. Be-careful shouldn't use alongside
   * isBotLeft().
   */
  private static boolean isMemberLeft(Message message) {
    return message.leftChatMember() != null;
  }

  /**
   * Returns true if Message is a PinnedMessage. Used alongside Pin Feature because when we Pin
   * message WebHook sends Update about it
   */
  private static boolean isPinnedMessage(Message message) {
    return message.pinnedMessage() != null;
  }

  private static String parseBotName(Update update) {
    return update.myChatMember().newChatMember().user().username();
  }

  private static CommandWithArgs parseCommand(final Message message) {
    final var e = message.entities()[0];
    final var start = e.offset();
    final var end = e.offset() + e.length();
    final var text = message.text();
    var command = text.substring(start, end).toLowerCase();
    final var args = text.substring(end).trim();
    if (command.contains("@")) {
      command = command.split("@")[0];
    }
    return new CommandWithArgs(command, SubCommand.of(args), args);
  }

  private static Optional<String> memberIdFromHashTag(final Message replyTo) {
    var entities = replyTo.entities();
    if (null != entities) {
      for (var e : entities) {
        if (e.type() == Type.hashtag) {
          final var hashTagString =
              replyTo.text().substring(e.offset() + 1, e.offset() + e.length());
          return Optional.of(hashTagString);
        }
      }
    }
    return Optional.empty();
  }

  private static String fromLong(Long raw) {
    return Long.toUnsignedString(raw, Character.MAX_RADIX);
  }

  private static Long toLong(String id) {
    return Long.parseUnsignedLong(id, Character.MAX_RADIX);
  }

  private PhotoSize largestPhoto(PhotoSize[] photos) {
    return Arrays.stream(photos).max(Comparator.comparing(PhotoSize::fileSize)).orElseThrow();
  }

  private static String userToString(User user) {
    final StringBuilder b = new StringBuilder();
    var name = user.firstName();
    if (null != name && !name.isEmpty()) {
      b.append(name);
    }
    name = user.lastName();
    if (null != name && !name.isEmpty()) {
      if (!b.isEmpty()) {
        b.append(' ');
      }
      b.append(name);
    }
    return b.isEmpty() ? user.username() : b.toString();
  }

  private record CommandWithArgs(String command, SubCommand subCommand, String args) {
    public boolean hasSubCommand() {
      return subCommand.type != SubCommand.SubCommandType.NONE;
    }
  }

  private static class SubCommand {
    private final SubCommandType type;
    private final String[] args;

    private SubCommand(SubCommandType type, String[] args) {
      this.type = type;
      this.args = args;
    }

    public static SubCommand of(String args) {
      String[] subCommands = args.split("__");

      if (subCommands.length > 1) {
        SubCommandType commandType = SubCommandType.parse(subCommands[0]);
        String[] newArgs = Arrays.copyOfRange(subCommands, 1, subCommands.length);

        return new SubCommand(commandType, newArgs);
      }

      return new SubCommand(SubCommandType.NONE, null);
    }

    private enum SubCommandType {
      NONE,
      JOIN,
      HOST;

      public static SubCommandType parse(String text) {
        return switch (text) {
          case "join" -> SubCommandType.JOIN;
          case "host" -> SubCommandType.HOST;
          default -> throw new ValidationException("Unsupported subCommand " + text);
        };
      }
    }
  }

  /**
   * More efficient implementation of a BinaryPayload lazily creates file url which is only needed
   * when routed to other connectors. Exposes fileId which is needed to re-route the file inside the
   * Telegram connector
   */
  public class TelegramBinaryMessage implements BinaryPayload {

    private final String messageId;
    private URI uri;
    private final String fileId;
    private final String fileName;
    private final String fileType;
    private final long fileSize;
    private final Instant created;
    private final Integer status;

    private TelegramBinaryMessage(
        String messageId,
        String fileId,
        String fileName,
        String fileType,
        long fileSize,
        Instant created,
        Integer status) {
      this.messageId = messageId;
      this.fileId = fileId;
      this.fileName = fileName;
      this.fileType = fileType;
      this.fileSize = fileSize;
      this.created = created;
      this.status = status;
    }

    @Override
    public String messageId() {
      return this.messageId;
    }

    /** Lazily retrieves URI. */
    @Override
    public URI uri() {
      if (null == this.uri) {
        GetFile getFile = new GetFile(this.fileId());
        GetFileResponse getFileResponse = TelegramConnector.this.bot.execute(getFile);
        File file = getFileResponse.file();
        String uriString = TelegramConnector.this.bot.getFullFilePath(file);
        this.uri = URI.create(uriString);
      }
      return this.uri;
    }

    String fileId() {
      return this.fileId;
    }

    @Override
    public String fileName() {
      return this.fileName;
    }

    @Override
    public String fileType() {
      return this.fileType;
    }

    @Override
    public long fileSize() {
      return this.fileSize;
    }

    @Override
    public Instant created() {
      return this.created;
    }

    @Override
    public Integer status() {
      return this.status;
    }
  }
}
