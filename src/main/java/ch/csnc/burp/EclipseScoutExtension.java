package ch.csnc.burp;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class EclipseScoutExtension implements BurpExtension {

  public static final String NAME = "Eclipse Scout";

  public static MontoyaApi api;

  @Override
  public void initialize(MontoyaApi api) {
    EclipseScoutExtension.api = api;
    api.extension().setName(NAME);
    api.logging().logToOutput("%s loaded".formatted(NAME));

    var versionTxt = "/version.txt";
    try (var stream = getClass().getResourceAsStream(versionTxt)) {
        var reader = new BufferedReader(new InputStreamReader(stream));
        reader.lines().forEach(api.logging()::logToOutput);
    } catch (Exception exc) {
        api.logging().logToError("Could not read %s".formatted(versionTxt));
        api.logging().logToError(exc);
    }

    var tab = new EclipseScoutTab();
    api.userInterface().registerSuiteTab(NAME, tab.getComponent());
  }

}
