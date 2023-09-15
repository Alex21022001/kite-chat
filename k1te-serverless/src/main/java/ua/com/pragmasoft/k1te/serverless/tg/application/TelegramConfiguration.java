package ua.com.pragmasoft.k1te.serverless.tg.application;

import java.net.URI;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.pengrad.telegrambot.TelegramBot;

import ua.com.pragmasoft.k1te.backend.router.domain.Channels;
import ua.com.pragmasoft.k1te.backend.router.domain.Router;
import ua.com.pragmasoft.k1te.backend.tg.TelegramConnector;

public class TelegramConfiguration {

  @Produces
  @ApplicationScoped
  public TelegramBot botClient(@ConfigProperty(name = "telegram.bot.token") String token) {
    return new TelegramBot(token);
  }

  @Produces
  @ApplicationScoped
  public TelegramConnector botConnector(TelegramBot botClient, Router router, Channels channels,
      @ConfigProperty(name = "telegram.webhook.endpoint") final URI base,
      @ConfigProperty(name = "ws.api.execution.endpoint") final URI wsApi) {
    return new TelegramConnector(botClient, router, channels, base, wsApi);
  }

}
