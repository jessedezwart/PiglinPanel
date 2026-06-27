package nl.jessedezwart.piglinpanel.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModGroup;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ModsTemplateRenderTest {

  private static SpringTemplateEngine engine() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    return engine;
  }

  @Test
  void rendersGroupedContentFragmentWithoutWebContext() {
    ServerProfile profile = new ServerProfile();
    profile.setModFolders(List.of(new ModFolder("mods", "mods", "mods")));
    ModUpdate update =
        new ModUpdate(
            "create",
            "Create",
            "v1",
            "v2",
            "5.1",
            Instant.parse("2026-06-10T12:00:00Z"),
            java.net.URI.create("https://example.com/create-5.1.jar"),
            "create-5.1.jar",
            "https://example.com/icon.png",
            "required",
            "required",
            1234L);
    ModFile active =
        new ModFile(
            "mods", "mods", "mods", "mods/create-5.1.jar", "create-5.1.jar", 20, false, "h1",
            update);
    ModFile archived =
        new ModFile(
            "mods",
            "mods",
            "mods",
            "mods/create-5.0.jar.disabled",
            "create-5.0.jar.disabled",
            19,
            true,
            "h2",
            update);
    ModGroup group =
        new ModGroup(
            "project:create",
            "Create",
            "https://example.com/icon.png",
            "mods",
            active,
            List.of(archived),
            update);

    Context context = new Context();
    context.setVariable("profile", profile);
    context.setVariable("serverId", "srv-1");
    context.setVariable("mods", List.of(active, archived));
    context.setVariable("groups", List.of(group));
    context.setVariable("updatesLoaded", true);
    context.setVariable("force", false);

    String html = engine().process("fragments/mods", Set.of("content"), context);

    assertThat(html).contains("Activate");
    assertThat(html).contains("/mods/activate");
  }
}
