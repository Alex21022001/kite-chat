/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023 */
package ua.com.pragmasoft.chat.telegram;

import static com.microsoft.playwright.assertions.LocatorAssertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.*;
import com.microsoft.playwright.Locator.LocatorOptions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.MouseButton;
import java.nio.file.Path;
import java.util.regex.Pattern;
import ua.com.pragmasoft.chat.ChatMessage;
import ua.com.pragmasoft.chat.ChatPage;

public final class TelegramChatPage extends ChatPage {
  private static final String TELEGRAM_WEB_URL = "https://web.telegram.org";

  private final Locator incomingMessages;
  private final Locator outgoingMessages;
  private final Locator fileAttachment;
  private final Locator sendFileButton;
  private final Locator input;
  private final Locator sendTextButton;
  private final Locator menuItems;

  private TelegramChatPage(Page page) {
    super(page);
    Locator chat = page.locator("#column-center").locator("div.chat");

    Locator messageGroups = chat.locator("div.bubbles").locator("div.bubbles-group");
    this.incomingMessages = messageGroups.locator(".bubble.is-in");
    this.outgoingMessages = messageGroups.locator(".bubble.is-out");

    this.fileAttachment = page.locator(".attach-file");
    this.sendFileButton =
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Send"));

    this.input = chat.locator("div.input-message-input:not(.input-field-input-fake)");
    this.sendTextButton = chat.locator("button.send");

    this.menuItems = page.locator(".btn-menu-items");
  }

  public static TelegramChatPage of(Page page, String chatTitle) {
    page.navigate(TELEGRAM_WEB_URL);
    Locator chatList = page.locator(".chatlist-top").getByRole(AriaRole.LINK);
    Locator chat =
        chatList.filter(
            new Locator.FilterOptions()
                .setHas(
                    page.locator(
                        "div.user-title", new Page.LocatorOptions().setHasText(chatTitle))));

    assertThat(chat).hasCount(1);
    chat.click();
    return new TelegramChatPage(page);
  }

  @Override
  public TelegramChatMessage lastMessage(MessageType type) {
    return switch (type) {
      case IN -> new TelegramChatMessage(this.incomingMessages.last());
      case OUT -> new TelegramChatMessage(this.outgoingMessages.last());
    };
  }

  @Override
  public void sendMessage(String text) {
    this.input.fill(text);
    this.sendTextButton.click();
    this.lastMessage(MessageType.OUT).hasText(text);
  }

  @Override
  public String uploadFile(Path pathToFile) {
    String fileName = this.attachFile(pathToFile, AttachmentType.DOC);

    this.lastMessage(MessageType.OUT).hasFile(fileName).waitMessageToBeUploaded(15_000);

    return fileName;
  }

  @Override
  public void uploadPhoto(Path pathToPhoto) {
    this.attachFile(pathToPhoto, AttachmentType.PHOTO);

    this.lastMessage(MessageType.OUT).isPhoto().waitMessageToBeUploaded(15_000);
  }

  public void replyMessage(TelegramChatMessage message, String text) {
    this.replyMessage(message.element(), text);
  }

  public void replyMessage(ElementHandle message, String text) {
    message.click(new ElementHandle.ClickOptions().setButton(MouseButton.RIGHT));
    this.menuItems
        .locator(".btn-menu-item", new LocatorOptions().setHasText(MenuItem.REPLY.value))
        .click();

    this.sendMessage(text);
  }

  private String attachFile(Path path, AttachmentType attachment) {
    FileChooser fileChooser =
        page.waitForFileChooser(
            () -> {
              this.fileAttachment.click();
              this.page.waitForTimeout(100); // If not wait - file attachment may not be invoked
              this.fileAttachment
                  .locator(".btn-menu-item")
                  .filter(new Locator.FilterOptions().setHasText(attachment.type))
                  .click();
            });
    fileChooser.setFiles(path);
    this.sendFileButton.click();
    this.page.waitForTimeout(500);

    return path.getFileName().toString();
  }

  public static class TelegramChatMessage implements ChatMessage {
    private final Locator messageLocator;

    private TelegramChatMessage(Locator messageLocator) {
      assertThat(messageLocator).hasClass(Pattern.compile("bubble"));
      this.messageLocator = messageLocator;
    }

    @Override
    public TelegramChatMessage hasText(String expected, double timeout) {
      Locator textMessage = this.messageLocator.locator(".message");
      assertThat(textMessage)
          .hasText(
              Pattern.compile(expected),
              new HasTextOptions().setUseInnerText(true).setIgnoreCase(true).setTimeout(timeout));
      return this;
    }

    @Override
    public TelegramChatMessage hasFile(String expectedFileName, double timeout) {
      Locator documentName = this.messageLocator.locator(".document-name");
      assertThat(documentName)
          .hasText(
              expectedFileName, new HasTextOptions().setUseInnerText(true).setTimeout(timeout));
      return this;
    }

    @Override
    public TelegramChatMessage isPhoto(double timeout) {
      Locator photo = this.messageLocator.locator(".attachment >> img.media-photo");
      assertThat(photo).isVisible(new IsVisibleOptions().setTimeout(timeout));
      return this;
    }

    @Override
    public void waitMessageToBeUploaded(double timeout) {
      Locator loader = this.messageLocator.locator(".preloader-container");
      assertThat(loader).not().isVisible(new IsVisibleOptions().setTimeout(timeout));
    }

    @Override
    public ElementHandle element() {
      return this.messageLocator.elementHandle();
    }
  }

  private enum AttachmentType {
    DOC("Document"),
    PHOTO("Photo or Video"),
    POLL("Poll");

    final String type;

    AttachmentType(String type) {
      this.type = type;
    }
  }

  private enum MenuItem {
    REPLY("Reply"),
    EDIT("Edit"),
    PIN("Pin"),
    DOWNLOAD("Download"),
    FORWARD("Forward"),
    SELECT("Select"),
    DELETE("Delete");

    final String value;

    MenuItem(String item) {
      this.value = item;
    }
  }
}
