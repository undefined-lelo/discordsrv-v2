package github.scarsz.discordsrv.util;

import github.scarsz.discordsrv.objects.MessageFormat;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

public class ComponentsV2Util {

    public static Container buildContainer(MessageFormat messageFormat, BiFunction<String, Boolean, String> translator) {
        List<ContainerChildComponent> components = new ArrayList<>();

        List<MessageFormat.SectionConfig> v2Sections = messageFormat.getComponentsV2Sections();

        if (v2Sections != null) {
            // Use config-specified V2 layout
            for (int i = 0; i < v2Sections.size(); i++) {
                MessageFormat.SectionConfig sec = v2Sections.get(i);
                List<TextDisplay> texts = new ArrayList<>();
                for (String t : sec.getTextDisplays()) {
                    String translated = translator.apply(t, true);
                    if (StringUtils.isNotBlank(translated)) {
                        texts.add(TextDisplay.of(translated));
                    }
                }
                if (texts.isEmpty()) continue;

                boolean hasThumb = StringUtils.isNotBlank(sec.getThumbnailUrl());
                if (i > 0) {
                    components.add(Separator.createDivider(Separator.Spacing.SMALL));
                }
                if (hasThumb) {
                    String thumbUrl = translator.apply(sec.getThumbnailUrl(), true);
                    components.add(Section.of(Thumbnail.fromUrl(thumbUrl), texts));
                } else {
                    components.addAll(texts);
                }
            }
        } else {
            // Auto-convert from Content + Embed
            String content = Optional.ofNullable(messageFormat.getContent())
                    .map(c -> translator.apply(c, true))
                    .filter(StringUtils::isNotBlank).orElse(null);

            String title = Optional.ofNullable(messageFormat.getTitle())
                    .map(t -> translator.apply(t, false))
                    .filter(StringUtils::isNotBlank).orElse(null);

            String description = Optional.ofNullable(messageFormat.getDescription())
                    .map(d -> translator.apply(d, true))
                    .filter(StringUtils::isNotBlank).orElse(null);

            String thumbnailUrl = resolveThumbnailUrl(messageFormat, translator);

            List<TextDisplay> firstGroupTexts = new ArrayList<>();
            if (content != null) {
                firstGroupTexts.add(TextDisplay.of(content));
            }
            if (title != null) {
                firstGroupTexts.add(TextDisplay.of("### " + title));
            }
            if (description != null) {
                firstGroupTexts.add(TextDisplay.of(description));
            }

            if (thumbnailUrl != null && !firstGroupTexts.isEmpty()) {
                components.add(Section.of(
                        Thumbnail.fromUrl(thumbnailUrl),
                        firstGroupTexts
                ));
            } else if (!firstGroupTexts.isEmpty()) {
                components.addAll(firstGroupTexts);
            }

            String authorName = Optional.ofNullable(messageFormat.getAuthorName())
                    .map(a -> translator.apply(a, false))
                    .filter(StringUtils::isNotBlank).orElse(null);
            if (authorName != null) {
                String authorImageUrl = Optional.ofNullable(messageFormat.getAuthorImageUrl())
                        .map(i -> translator.apply(i, true))
                        .filter(StringUtils::isNotBlank).orElse(null);
                String authorText = authorName;
                if (authorImageUrl != null) {
                    authorText = "![](" + authorImageUrl + ") " + authorName;
                }
                if (!components.isEmpty()) {
                    components.add(Separator.createDivider(Separator.Spacing.SMALL));
                }
                components.add(TextDisplay.of("-# " + authorText));
            }

            if (messageFormat.getFields() != null && !messageFormat.getFields().isEmpty()) {
                if (!components.isEmpty()) {
                    components.add(Separator.createDivider(Separator.Spacing.SMALL));
                }
                for (net.dv8tion.jda.api.entities.MessageEmbed.Field field : messageFormat.getFields()) {
                    String name = translator.apply(field.getName(), true);
                    String value = translator.apply(field.getValue(), true);
                    if (StringUtils.isNotBlank(name) || StringUtils.isNotBlank(value)) {
                        components.add(TextDisplay.of("**" + name + "**\n" + value));
                    }
                }
            }

            String footerText = Optional.ofNullable(messageFormat.getFooterText())
                    .map(f -> translator.apply(f, true))
                    .filter(StringUtils::isNotBlank).orElse(null);
            if (footerText != null) {
                if (!components.isEmpty()) {
                    components.add(Separator.createDivider(Separator.Spacing.SMALL));
                }
                String footerIconUrl = Optional.ofNullable(messageFormat.getFooterIconUrl())
                        .map(f -> translator.apply(f, true))
                        .filter(StringUtils::isNotBlank).orElse(null);
                String footerDisplay = footerText;
                if (footerIconUrl != null) {
                    footerDisplay = "![](" + footerIconUrl + ") " + footerText;
                }
                components.add(TextDisplay.of("-# " + footerDisplay));
            }
        }

        if (components.isEmpty()) {
            return null;
        }

        Container container = Container.of(components);

        Integer accentColor = messageFormat.getComponentsV2AccentColor();
        if (accentColor != null) {
            container = container.withAccentColor(accentColor);
        } else {
            int color = messageFormat.getColorRaw();
            if (color != -1 && color != net.dv8tion.jda.api.entities.Role.DEFAULT_COLOR_RAW) {
                container = container.withAccentColor(color);
            }
        }

        return container;
    }

    private static String resolveThumbnailUrl(MessageFormat mf, BiFunction<String, Boolean, String> translator) {
        String v2Thumb = mf.getComponentsV2ThumbnailUrl();
        if (StringUtils.isNotBlank(v2Thumb)) {
            return translator.apply(v2Thumb, true);
        }
        if (StringUtils.isNotBlank(mf.getThumbnailUrl())) {
            return translator.apply(mf.getThumbnailUrl(), true);
        }
        return null;
    }
}
