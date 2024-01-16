/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023-2024 */
package ua.com.pragmasoft.k1te.backend.router.infrastructure;

import java.time.Instant;
import java.util.*;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import ua.com.pragmasoft.k1te.backend.router.domain.*;
import ua.com.pragmasoft.k1te.backend.shared.KiteException;
import ua.com.pragmasoft.k1te.backend.shared.NotFoundException;

public class DynamoDbMessages implements Messages {

  public static final String MESSAGES_TABLE = "Messages";
  private static final String MESSAGES_TIME_INDEX = "MessageTime";

  private static final String MESSAGES_ID_ATTRIBUTE = "id";
  private static final String MESSAGES_TIME_ATTRIBUTE = "time";
  private static final String MESSAGES_CONTENT_ATTRIBUTE = "content";
  private static final String MESSAGES_MESSAGE_ID_ATTRIBUTE = "messageId";

  private final Channels channels;

  private final String messagesTableName;
  private final DynamoDbEnhancedAsyncClient enhancedDynamo;
  private final DynamoDbAsyncClient dynamoDbClient;
  private final DynamoDbAsyncTable<DynamoDbHistoryMessage> messageTable;

  public DynamoDbMessages(
      Channels channels,
      DynamoDbEnhancedAsyncClient enhancedDynamo,
      DynamoDbAsyncClient dynamoDbClient,
      String serverlessEnvironmentName) {
    this.channels = channels;
    this.dynamoDbClient = dynamoDbClient;
    this.messagesTableName =
        null != serverlessEnvironmentName
            ? serverlessEnvironmentName + '.' + MESSAGES_TABLE
            : MESSAGES_TABLE;
    this.enhancedDynamo = enhancedDynamo;
    this.messageTable =
        this.enhancedDynamo.table(
            this.messagesTableName, TableSchema.fromClass(DynamoDbHistoryMessage.class));
  }

  @Override
  public HistoryMessage persist(Member owner, String messageId, String content, Instant time) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(messageId);
    Objects.requireNonNull(content);
    Objects.requireNonNull(time);
    String id = DynamoDbHistoryMessage.buildId(owner.getChannelName(), owner.getId());

    DynamoDbHistoryMessage dbMessage = new DynamoDbHistoryMessage(id, messageId, content, time);
    try {
      this.messageTable.putItem(dbMessage).join();
      return dbMessage;
    } catch (Exception e) {
      throw new KiteException(e.getMessage(), e);
    }
  }

  @Override
  public DynamoDbHistoryMessage find(Member member, String messageId) {
    String id = DynamoDbHistoryMessage.buildId(member.getChannelName(), member.getId());
    Key messageKey = Key.builder().partitionValue(id).sortValue(messageId).build();

    DynamoDbHistoryMessage message = this.messageTable.getItem(messageKey).join();
    if (message == null) throw new NotFoundException("History Message Not Found");

    return message;
  }

  /**
   * - if lastMessageId == null && lastMessageTime == null - returns all item collection - if limit
   * == null - return all items up to 1 MB - if both lastMessageId and lastActiveTime are provided -
   * lastActiveTime has priority - if connectionUri is provided - lastMessageTime will be for this
   * specific connection - if there is no messagesOwner provided, owner will be found by their
   * connectionUri
   */
  @Override
  public List<HistoryMessage> findAll(MessagesRequest request) {
    String connectionUri = request.getConnectionUri();
    Member member = request.getMessagesOwner();
    Instant lastMessageTime = request.getLastMessageTime();
    String lastMessageId = request.getLastMessageId();
    Integer limit = request.getLimit();
    boolean lastMessageByConnection = request.isLastMessageByConnection();

    if (connectionUri == null && member == null)
      throw new IllegalStateException(
          "Member and Connection are not provided, must be at least one of them");

    if (member == null) {
      member = this.channels.find(connectionUri);
    }
    if (lastMessageByConnection) {
      if (connectionUri == null) {
        throw new IllegalStateException(
            "lastMessageByConnection is true but not ConnectionUri is specified");
      }
      DynamoDbMember dbMember = (DynamoDbMember) member;
      lastMessageTime = dbMember.getLastMessageTimeForConnection(connectionUri);
    }

    String id = DynamoDbHistoryMessage.buildId(member.getChannelName(), member.getId());
    String keyCondition = "#id = :id ";

    Map<String, String> names = new HashMap<>();
    names.put("#id", MESSAGES_ID_ATTRIBUTE);
    Map<String, AttributeValue> values = new HashMap<>();
    values.put(":id", AttributeValue.fromS(id));

    if (lastMessageTime != null) {
      keyCondition = keyCondition.concat("AND #time > :time");
      names.put("#time", MESSAGES_TIME_ATTRIBUTE);
      values.put(":time", AttributeValue.fromS(lastMessageTime.toString()));
    } else if (lastMessageId != null && !lastMessageId.isEmpty()) {
      HistoryMessage message = this.find(member, lastMessageId);
      Instant messageTime = message.getTime();
      keyCondition = keyCondition.concat("AND #time > :time");
      names.put("#time", MESSAGES_TIME_ATTRIBUTE);
      values.put(":time", AttributeValue.fromS(messageTime.toString()));
    }

    return this.dynamoDbClient
        .query(
            QueryRequest.builder()
                .tableName(messagesTableName)
                .indexName(MESSAGES_TIME_INDEX)
                .keyConditionExpression(keyCondition)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .scanIndexForward(false)
                .limit(limit)
                .build())
        .join()
        .items()
        .stream()
        .map(this::buildMessage)
        .sorted(Comparator.comparing(HistoryMessage::getTime))
        .toList();
  }

  private HistoryMessage buildMessage(Map<String, AttributeValue> map) {
    String historyMessageId = map.get(MESSAGES_ID_ATTRIBUTE).s();
    String messageId = map.get(MESSAGES_MESSAGE_ID_ATTRIBUTE).s();
    String content = map.get(MESSAGES_CONTENT_ATTRIBUTE).s();
    String time = map.get(MESSAGES_TIME_ATTRIBUTE).s();
    return new DynamoDbHistoryMessage(historyMessageId, messageId, content, Instant.parse(time));
  }
}
