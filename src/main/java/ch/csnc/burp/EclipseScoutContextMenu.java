package ch.csnc.burp;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JMenuItem;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

public class EclipseScoutContextMenu implements ContextMenuItemsProvider {

  private Consumer<HttpRequestResponse> requestResponseConsumer;

  public EclipseScoutContextMenu(Consumer<HttpRequestResponse> requestResponseConsumer) {
      this.requestResponseConsumer = requestResponseConsumer;
  }

  @Override
  public List<Component> provideMenuItems(ContextMenuEvent event) {
      var sendTo = new JMenuItem("Send to %s".formatted(EclipseScoutExtension.NAME));
      sendTo.addActionListener((actionEvent) -> {
        event.selectedRequestResponses().forEach(this.requestResponseConsumer::accept);
      });
      return List.of(sendTo);
  }

}
