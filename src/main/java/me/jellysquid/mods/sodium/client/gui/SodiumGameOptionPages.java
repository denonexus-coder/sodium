package me.jellysquid.mods.sodium.client.gui;

import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gl.arena.staging.MappedStagingBuffer;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.binding.compat.VanillaBooleanOptionBinding;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.MinecraftOptionsStorage;
import me.jellysquid.mods.sodium.client.gui.options.storage.SodiumOptionsStorage;
import me.jellysquid.mods.sodium.client.render.chunk.lighting.ColoredLighting;
import me.jellysquid.mods.sodium.client.util.FrameTrace;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.option.*;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Sodium's own pages.
 *
 * <p>The names and descriptions are literal Portuguese rather than translation keys: the point of
 * this build is that every row says what the value changes and what it costs, and a key that falls
 * back to English would defeat that. Each description names the mechanism (a plane test, a fence
 * wait, an upload, a rebuild that blocks the frame) instead of restating the label, because the
 * label is already on the row.
 *
 * <p>The bindings and flags are untouched. The text is the only thing this class changes.
 */
// TODO: Rename in Sodium 0.6
public class SodiumGameOptionPages {
    private static final SodiumOptionsStorage sodiumOpts = new SodiumOptionsStorage();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

    /**
     * Iris 1.7.x injects into this class with Mixin slices anchored on vanilla translation keys:
     * {@code options.renderDistance} → {@code options.simulationDistance} in {@link #general()},
     * and {@code options.graphics} → {@code options.renderClouds} in {@link #quality()}.
     *
     * <p>This build shows literal Portuguese, so those keys no longer occur naturally. Without the
     * constants, Iris fails its injection check with "Scanned 0 target(s)" and the game crashes the
     * moment the options screen opens. These no-op calls keep the exact string constants in the
     * bytecode, in the order and position the slices expect; they run once while the pages are
     * built and have no other effect.
     */
    private static void irisSliceAnchor(String key) {
        // Intentionally empty: the argument is the anchor, not data used at runtime.
    }

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        Option<Integer> viewDistance = OptionImpl.createBuilder(int.class, vanillaOpts)
                .setName(Text.literal("Distância de renderização"))
                .setTooltip(Text.literal("Até onde o terreno é construído e desenhado, em chunks de 16×16. "
                        + "Cada passo alarga a área ao quadrado: 12 chunks mostram mais do dobro de terreno que 8.\n\n"
                        + "O que afeta: quantas secções de terreno entram na passagem de desenho, a memória das malhas "
                        + "e o número de chamadas ao driver. É a opção com maior efeito na taxa de fotogramas; "
                        + "baixar dois chunks costuma valer mais que todas as outras juntas.\n\n"
                        + "Não mexe na simulação: o servidor interno continua a carregar e a simular o que a opção "
                        + "seguinte mandar. Precisa de recarregar o renderizador."))
                .setControl(option -> new SliderControl(option, 2, 32, 1, chunks()))
                .setBinding((options, value) -> options.getViewDistance().setValue(value), options -> options.getViewDistance().getValue())
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Integer> simulationDistance = OptionImpl.createBuilder(int.class, vanillaOpts)
                .setName(Text.literal("Distância de simulação"))
                .setTooltip(Text.literal("Até onde o servidor interno carrega e simula blocos, entidades e ticks, em chunks. "
                        + "É independente da distância de renderização: podes desenhar 16 chunks e simular 5.\n\n"
                        + "O que afeta: carga de CPU, ticks de mobs, fornos, crescimento de plantas, redstone e a memória "
                        + "das chunks carregadas. Uma distância de simulação maior que a de renderização mantém vivo o que "
                        + "não vês; menor alivia o CPU sem tirar paisagem.\n\n"
                        + "Precisa de recarregar o renderizador."))
                .setControl(option -> new SliderControl(option, 5, 32, 1, chunks()))
                .setBinding((options, value) -> options.getSimulationDistance().setValue(value), options -> options.getSimulationDistance().getValue())
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Integer> brightness = OptionImpl.createBuilder(int.class, vanillaOpts)
                .setName(Text.literal("Brilho"))
                .setTooltip(Text.literal("Multiplica o brilho final da imagem — o gamma do jogo. Não altera a luz calculada "
                        + "no mundo: só levanta o que já foi calculado, por isso não revela nada numa caverna sem luz, "
                        + "apenas torna o escuro menos escuro.\n\n"
                        + "O que afeta: só a cor final dos píxeis. Custo zero por frame. Vale no frame seguinte."))
                .setControl(opt -> new SliderControl(opt, 0, 100, 1, brightness()))
                .setBinding((opts, value) -> opts.getGamma().setValue(value * 0.01D), (opts) -> (int) (opts.getGamma().getValue() / 0.01D))
                .build();

        OptionGroup.Builder world = OptionGroup.createBuilder()
                .setName(Text.literal("Mundo"));

        // Iris slices general() between these two keys to insert its shadow-distance slider. The
        // anchors keep the LDC constants in this method now that the visible text is Portuguese.
        irisSliceAnchor("options.renderDistance");
        world.add(viewDistance);
        irisSliceAnchor("options.simulationDistance");
        world.add(simulationDistance);
        world.add(brightness);

        groups.add(world.build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Ecrã e ritmo"))
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Escala da interface"))
                        .setTooltip(Text.literal("Tamanho do HUD e dos menus, em múltiplos. «Automático» escolhe o maior inteiro que "
                                + "cabe no ecrã.\n\n"
                                + "O que afeta: o tamanho do texto e dos botões, e a resolução lógica a que a interface é desenhada. "
                                + "Uma escala maior gasta mais píxeis e deixa menos para o mundo; uma escala menor mostra mais "
                                + "linhas de opções. Ao mudar, o jogo recalcula a janela e o que está em cache."))
                        .setControl(option -> new SliderControl(option, 0, MinecraftClient.getInstance().getWindow().calculateScaleFactor(0, MinecraftClient.getInstance().forcesUnicodeFont()), 1, guiScale()))
                        .setBinding((opts, value) -> {
                            opts.getGuiScale().setValue(value);

                            MinecraftClient client = MinecraftClient.getInstance();
                            client.onResolutionChanged();
                        }, opts -> opts.getGuiScale().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Ecrã inteiro"))
                        .setTooltip(Text.literal("Pede à janela para ocupar o ecrã todo, sem barra de título. Se o sistema recusar, "
                                + "a opção volta a falso.\n\n"
                                + "O que afeta: a resolução a que o mundo é desenhado passa a ser a do ecrã. Numa GPU limitada, "
                                + "mais píxeis custam mais por frame — em telemóvel, o ecrã inteiro pode ser mais caro que uma "
                                + "janela mais pequena. Não muda o campo de visão."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.getFullscreen().setValue(value);

                            MinecraftClient client = MinecraftClient.getInstance();
                            Window window = client.getWindow();

                            if (window != null && window.isFullscreen() != opts.getFullscreen().getValue()) {
                                window.toggleFullscreen();

                                // The client might not be able to enter full-screen mode
                                opts.getFullscreen().setValue(window.isFullscreen());
                            }
                        }, (opts) -> opts.getFullscreen().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Sincronização vertical (VSync)"))
                        .setTooltip(Text.literal("Prende a apresentação do frame ao refrescamento do ecrã: o frame só aparece no "
                                + "início de um ciclo do ecrã.\n\n"
                                + "O que afeta: a latência de entrada e a regularidade. Se o jogo não consegue produzir um frame "
                                + "por ciclo, o VSync corta para metade da taxa (60→30→20), o que se sente como um degrau. "
                                + "Num telemóvel costuma custar mais do que ganha; desligado, o limite de FPS faz o mesmo "
                                + "trabalho sem prender a latência."))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getEnableVsync()))
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Limite de FPS"))
                        .setTooltip(Text.literal("Teto de frames por segundo. Acima do refrescamento do ecrã não se vê diferença; "
                                + "abaixo, poupa bateria e calor.\n\n"
                                + "O que afeta: o tempo que o CPU passa a desenhar em vez de dormir e o aquecimento da GPU. "
                                + "Com VSync ligado, este valor só conta se for menor que o refrescamento. «Sem limite» tira "
                                + "o teto: numa PowerVR aquece depressa e acaba em throttling, com a taxa a cair mais do que "
                                + "se tivesse um teto estável."))
                        .setControl(option -> new SliderControl(option, 10, 260, 10, fpsLimit()))
                        .setBinding((opts, value) -> {
                            opts.getMaxFps().setValue(value);
                            MinecraftClient.getInstance().getWindow().setFramerateLimit(value);
                        }, opts -> opts.getMaxFps().getValue())
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Interface"))
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Balanço da câmara"))
                        .setTooltip(Text.literal("Faz a câmara oscilar ao andar. Puramente cosmético.\n\n"
                                + "O que afeta: a matriz de visão de cada frame, e nada mais. Desligar não muda o desempenho; "
                                + "serve para quem se sente mal com o movimento."))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getBobView()))
                        .build())
                .add(OptionImpl.createBuilder(AttackIndicator.class, vanillaOpts)
                        .setName(Text.literal("Indicador de ataque"))
                        .setTooltip(Text.literal("Onde aparece a barra que mostra a recarga do ataque: na mira, na barra rápida ou "
                                + "em lado nenhum.\n\n"
                                + "O que afeta: só o HUD. Um elemento de interface a menos é um pouco menos de desenho, sem "
                                + "qualquer efeito no mundo."))
                        .setControl(opts -> new CyclingControl<>(opts, AttackIndicator.class, new Text[] { Text.literal("Desligado"), Text.literal("Na mira"), Text.literal("Na barra") }))
                        .setBinding((opts, value) -> opts.getAttackIndicator().setValue(value), (opts) -> opts.getAttackIndicator().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Indicador de gravação"))
                        .setTooltip(Text.literal("Mostra um símbolo enquanto o jogo grava o mundo no disco.\n\n"
                                + "O que afeta: só o HUD. A gravação acontece na mesma; isto apenas a mostra."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getShowAutosaveIndicator().setValue(value), opts -> opts.getShowAutosaveIndicator().getValue())
                        .build())
                .build());

        return new OptionPage(Text.literal("Geral"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        Option<GraphicsMode> graphics = OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
                .setName(Text.literal("Gráficos"))
                .setTooltip(Text.literal("Modo de gráficos do vanilla. Serve de valor por omissão às opções que estiverem em "
                        + "«Padrão»: com Rápido, folhas e precipitação resolvem para as versões opacas e próximas; "
                        + "com Bom ou Fabuloso, para as versões completas.\n\n"
                        + "Fabuloso acrescenta ainda o pipeline de framebuffers para translúcidos e nuvens, com várias "
                        + "passagens a mais por frame.\n\n"
                        + "O que afeta: um conjunto de opções de uma vez e a arquitetura do renderizador. Numa PowerVR, "
                        + "Fabuloso multiplica o custo de cada frame. Precisa de recarregar o renderizador."))
                .setControl(option -> new CyclingControl<>(option, GraphicsMode.class, new Text[] { Text.literal("Rápido"), Text.literal("Bom"), Text.literal("Fabuloso") }))
                .setBinding(
                        (opts, value) -> opts.getGraphicsMode().setValue(value),
                        opts -> opts.getGraphicsMode().getValue())
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        OptionGroup.Builder mode = OptionGroup.createBuilder()
                .setName(Text.literal("Modo de renderização"));

        // Iris slices quality() between these two keys to replace the graphics option with its
        // limited video-settings button and to append its color-space option.
        irisSliceAnchor("options.graphics");
        mode.add(graphics);
        irisSliceAnchor("options.renderClouds");
        groups.add(mode.build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Ambiente"))
                .add(OptionImpl.createBuilder(CloudRenderMode.class, vanillaOpts)
                        .setName(Text.literal("Nuvens"))
                        .setTooltip(Text.literal("Desligadas: sem nuvens. Rápidas: uma camada plana. Bonitas: nuvens em 3D, com "
                                + "geometria própria por bloco.\n\n"
                                + "O que afeta: a camada de nuvens cobre uma fração grande do ecrã e é desenhada depois do "
                                + "terreno, por isso paga preenchimento e não só geometria. As Bonitas gastam muitos triângulos; "
                                + "as Rápidas quase nada. Desligar é a maior poupança das três."))
                        .setControl(option -> new CyclingControl<>(option, CloudRenderMode.class, new Text[] { Text.literal("Desligadas"), Text.literal("Rápidas"), Text.literal("Bonitas") }))
                        .setBinding((opts, value) -> {
                            opts.getCloudRenderMode().setValue(value);

                            if (MinecraftClient.isFabulousGraphicsOrBetter()) {
                                Framebuffer framebuffer = MinecraftClient.getInstance().worldRenderer.getCloudsFramebuffer();
                                if (framebuffer != null) {
                                    framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
                                }
                            }
                        }, opts -> opts.getCloudRenderMode().getValue())
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(Text.literal("Chuva e neve"))
                        .setTooltip(Text.literal("Rápidas desenha a precipitação perto do jogador; Bonitas estende-a pela distância "
                                + "de renderização, como no vanilla.\n\n"
                                + "O que afeta: o número de quads de chuva/neve e o preenchimento do ecrã durante uma tempestade. "
                                + "Com chuva forte a diferença nota-se, porque cada gota é um quad translúcido. Não altera a "
                                + "simulação do tempo."))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class, new Text[] { Text.literal("Padrão"), Text.literal("Bonitas"), Text.literal("Rápidas") }))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(Text.literal("Folhas"))
                        .setTooltip(Text.literal("Rápidas desenha as folhas como um bloco sólido, sem alpha test, e esconde as faces "
                                + "entre folhas vizinhas mesmo de tipos diferentes. Bonitas usa cutout com mipmap: os "
                                + "buracos da textura aparecem e as faces interiores de tipos diferentes são mantidas.\n\n"
                                + "O que afeta: a geometria das árvores e o que se vê através delas. Sólidas gastam menos "
                                + "triângulos e menos preenchimento, mas escondem o que está atrás e mudam o aspeto de um "
                                + "bosque; cutout é mais fiel e um pouco mais caro. Nenhuma das duas usa blending, por isso "
                                + "não há artefactos de ordenação."))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class, new Text[] { Text.literal("Padrão"), Text.literal("Bonitas"), Text.literal("Rápidas") }))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(ParticlesMode.class, vanillaOpts)
                        .setName(Text.literal("Partículas"))
                        .setTooltip(Text.literal("Teto de partículas simultâneas: todas, reduzidas (cerca de metade) ou mínimas.\n\n"
                                + "O que afeta: quantas partículas o jogo simula e desenha. Cada partícula é um quad com "
                                + "blending, e o blending custa largura de banda. Reduzir corta primeiro as partículas menos "
                                + "importantes. Não desliga o sistema; para isso existe a opção do Flywheel."))
                        .setControl(option -> new CyclingControl<>(option, ParticlesMode.class, new Text[] { Text.literal("Todas"), Text.literal("Reduzidas"), Text.literal("Mínimas") }))
                        .setBinding((opts, value) -> opts.getParticles().setValue(value), (opts) -> opts.getParticles().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Iluminação e entidades"))
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Oclusão ambiente"))
                        .setTooltip(Text.literal("Escurece os cantos entre blocos conforme os vizinhos, o que dá volume às formas. "
                                + "É calculada ao construir a chunk, não por frame.\n\n"
                                + "O que afeta: a cor dos vértices da malha e, por isso, o tempo de construção da chunk — não a "
                                + "taxa de fotogramas depois de construída. Desligar dá cantos uniformes e planos, e malhas um "
                                + "pouco mais rápidas de construir. Precisa de recarregar o renderizador."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getAo().setValue(value), opts -> opts.getAo().getValue())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Mistura de biomas"))
                        .setTooltip(Text.literal("Distância, em blocos, ao longo da qual as cores de bioma (relva, folhas, água) "
                                + "transitam de um bioma para outro.\n\n"
                                + "O que afeta: só a cor da malha. 1 quase não mistura; 7 é uma transição larga. Cada passo "
                                + "alarga a vizinhança que tem de ser lida por bloco, e o tempo de construção da chunk cresce "
                                + "com o quadrado do raio. Não afeta a taxa de fotogramas depois de construída."))
                        .setControl(option -> new SliderControl(option, 1, 7, 1, biomeBlend()))
                        .setBinding((opts, value) -> opts.getBiomeBlendRadius().setValue(value), opts -> opts.getBiomeBlendRadius().getValue())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Distância das entidades"))
                        .setTooltip(Text.literal("Multiplicador da distância a que as entidades são desenhadas, sobre a distância de "
                                + "renderização. 50% desenha mobs só até metade dessa distância; 500% deixa de cortar "
                                + "dentro do que está carregado.\n\n"
                                + "O que afeta: quantas entidades chegam ao renderizador. As que ficam fora deixam de ser "
                                + "desenhadas mas continuam a ser simuladas. Num mundo com muitos mobs, itens no chão ou "
                                + "carrinhos, é uma das maiores poupanças por frame."))
                        .setControl(option -> new SliderControl(option, 50, 500, 25, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.getEntityDistanceScaling().setValue(value / 100.0), opts -> Math.round(opts.getEntityDistanceScaling().getValue().floatValue() * 100.0F))
                        .setImpact(OptionImpact.HIGH)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.literal("Sombras das entidades"))
                        .setTooltip(Text.literal("Desenha um quad escuro no chão debaixo de mobs e outras entidades.\n\n"
                                + "O que afeta: uma passagem de blending por entidade visível, por cima do terreno. Com muitos "
                                + "mobs juntos são muitos quads translúcidos. Desligar poupa esse preenchimento; a entidade "
                                + "fica sem referência de altura no chão."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getEntityShadows().setValue(value), opts -> opts.getEntityShadows().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Vinheta"))
                        .setTooltip(Text.literal("Escurece as bordas do ecrã quando o jogador está em zonas escuras.\n\n"
                                + "O que afeta: uma passagem de ecrã inteiro com blending, aplicada depois do mundo. Custa "
                                + "preenchimento proporcional à resolução. Desligar deixa a imagem uniforme e poupa essa passagem."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .build())
                .build());


        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Texturas"))
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Mipmaps"))
                        .setTooltip(Text.literal("Número de níveis de mipmap gerados para as texturas de bloco; cada nível tem "
                                + "metade da resolução do anterior.\n\n"
                                + "O que afeta: o serrilhado e o cintilar das texturas ao longe. Com 0 os blocos distantes "
                                + "cintilam; com 4 ficam estáveis. Custa memória (cerca de um terço a mais no total) e obriga "
                                + "a gerar e enviar os níveis. Não afeta a geometria. Precisa de recarregar os assets."))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.getMipmapLevels().setValue(value), opts -> opts.getMipmapLevels().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .build());


        return new OptionPage(Text.literal("Qualidade"), ImmutableList.copyOf(groups));
    }

    public static OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Construção de chunks"))
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.literal("Threads de construção de chunks"))
                        .setTooltip(Text.literal("Quantas threads constroem e atualizam as malhas das chunks. 0 deixa o Sodium "
                                + "escolher: um terço dos núcleos ou núcleos−6, o que for maior, entre 1 e 10.\n\n"
                                + "O que afeta: a rapidez com que uma alteração de bloco aparece e o tamanho da fila de chunks a "
                                + "carregar. Mais threads encurtam a fila, mas competem com o render e com o servidor interno no "
                                + "mesmo CPU; num telemóvel, passado o ponto de saturação, aumentam o jitter dos frames. O valor "
                                + "pedido é limitado ao número de processadores. Precisa de recarregar o renderizador."))
                        .setControl(o -> new SliderControl(o, 0, Runtime.getRuntime().availableProcessors(), 1, threads()))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.chunkBuilderThreads = value, opts -> opts.performance.chunkBuilderThreads)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Adiar sempre as atualizações de chunks"))
                        .setTooltip(Text.literal("Com isto ligado, nenhuma reconstrução de chunk bloqueia o frame — nem as marcadas "
                                + "como importantes, que são as que estão debaixo do jogador.\n\n"
                                + "O que afeta: um bloco colocado ou removido perto de ti deixa de forçar o frame a esperar pela "
                                + "nova malha; aparece quando o construtor a terminar. É a diferença entre um engasgo e um "
                                + "atraso visível. Desligado, o frame espera e o bloco aparece de imediato."
                                + " Vale no frame seguinte."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.alwaysDeferChunkUpdates = value, opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .build()
        );

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Desenho"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Culling de faces por secção"))
                        .setTooltip(Text.literal("Para cada secção de 16×16×16, testa de que lado o plano da câmara está e submete "
                                + "só os grupos de faces que podem ser vistos. Não é oclusão por bloco: é um teste de plano "
                                + "contra a posição da câmara.\n\n"
                                + "Neste build as faces mantidas são alargadas até formarem um intervalo contínuo, para a secção "
                                + "continuar a ser uma só chamada de desenho; as faces extra são viradas para trás e o cull do "
                                + "OpenGL descarta-as, por isso os píxeis não mudam.\n\n"
                                + "Desligar submete as seis faces. Só vale a pena se um resource pack deixar buracos nos blocos. "
                                + "Precisa de recarregar o renderizador."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useBlockFaceCulling = value, opts -> opts.performance.useBlockFaceCulling)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Oclusão por nevoeiro"))
                        .setTooltip(Text.literal("Se o nevoeiro estiver totalmente opaco, os chunks para lá do fim do nevoeiro não "
                                + "chegam a ser submetidos. Se o nevoeiro for translúcido a opção não faz nada — é isso que "
                                + "impede buracos entre o céu e o nevoeiro.\n\n"
                                + "O que afeta: quantas secções entram na passagem de terreno. Dentro de água ou em nevoeiro "
                                + "denso o alcance efetivo cai muito e a poupança é grande; em terra firme, com nevoeiro curto "
                                + "mas transparente, não muda nada."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.performance.useFogOcclusion = value, opts -> opts.performance.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Culling de entidades"))
                        .setTooltip(Text.literal("Uma entidade só é desenhada se a caixa dela intersectar pelo menos uma secção "
                                + "marcada como visível. Usa o grafo de visibilidade que o terreno já calculou, por isso não "
                                + "há um teste de frustum extra.\n\n"
                                + "O que afeta: entidades escondidas atrás de terreno deixam de ser desenhadas. Entidades com "
                                + "contorno ou nome, e entidades muito grandes, são sempre desenhadas. Desligar só faz trabalho "
                                + "a mais, sem mudar a imagem."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useEntityCulling = value, opts -> opts.performance.useEntityCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Animar só texturas visíveis"))
                        .setTooltip(Text.literal("Uma textura animada só avança e é reenviada para a GPU se tiver sido marcada como "
                                + "visível no frame anterior. As invisíveis continuam a contar o tempo, mas não gastam upload.\n\n"
                                + "O que afeta: o tráfego de upload por tick. Com muitos sprites animados (fogo, água, lava, "
                                + "packs com muitas animações) é uma das maiores poupanças de CPU. Se alguma textura deixar de "
                                + "animar, desliga isto: é o único efeito colateral possível."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.animateOnlyVisibleTextures = value, opts -> opts.performance.animateOnlyVisibleTextures)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Contexto OpenGL sem verificação de erros"))
                        .setTooltip(Text.literal("Cria o contexto com GL_KHR_no_error: o driver deixa de validar cada chamada e de "
                                + "gerar códigos de erro.\n\n"
                                + "O que afeta: o custo de CPU por chamada OpenGL, sobretudo quando há muitas chamadas pequenas. "
                                + "O preço é que um erro deixa de ser reportado e passa a produzir corrupção silenciosa, o que "
                                + "torna qualquer diagnóstico muito mais difícil.\n\n"
                                + "Só aparece quando o driver suporta GL_KHR_no_error ou OpenGL 4.6. O contexto é "
                                + "criado no arranque, por isso precisa de reiniciar o jogo."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.useNoErrorGLContext = value, opts -> opts.performance.useNoErrorGLContext)
                        .setEnabled(supportsNoErrorContext())
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .build());

        return new OptionPage(Text.literal("Desempenho"), ImmutableList.copyOf(groups));
    }

    private static boolean supportsNoErrorContext() {
        GLCapabilities capabilities = GL.getCapabilities();
        return (capabilities.OpenGL46 || capabilities.GL_KHR_no_error)
                && !Workarounds.isWorkaroundEnabled(Workarounds.Reference.NO_ERROR_CONTEXT_UNSUPPORTED);
    }

    public static OptionPage advanced() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Transferências"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Mapeamento persistente"))
                        .setTooltip(Text.literal("Usa buffers de staging mapeados de forma persistente para enviar geometria de "
                                + "chunks: o CPU escreve diretamente na memória que a GPU vê, sem copiar para um buffer "
                                + "intermédio.\n\n"
                                + "O que afeta: o custo de upload das malhas e a sincronização entre CPU e GPU. Poupa uma cópia "
                                + "por upload. Se vires corrupção gráfica, desliga primeiro isto.\n\n"
                                + "Só aparece quando o driver suporta OpenGL 4.4 ou ARB_buffer_storage. Precisa de recarregar o "
                                + "renderizador."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabled(MappedStagingBuffer.isSupported(RenderDevice.INSTANCE))
                        .setBinding((opts, value) -> opts.advanced.useAdvancedStagingBuffers = value, opts -> opts.advanced.useAdvancedStagingBuffers)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("CPU"))
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.literal("Frames em avanço no CPU"))
                        .setTooltip(Text.literal("Quantos frames podem estar em voo para a GPU antes de o CPU esperar. No início de "
                                + "cada frame o Sodium espera pelas fences antigas até ficarem só N.\n\n"
                                + "O que afeta: latência e estabilidade. 0 obriga o CPU a esperar que a GPU termine cada frame — "
                                + "latência mínima, mas qualquer variação da GPU aparece como engasgo. Valores altos deixam o CPU "
                                + "correr à frente e absorvem variações, à custa de mais latência e de mais memória em uso.\n\n"
                                + "É uma ferramenta de diagnóstico, não um botão de desempenho."))
                        .setControl(opt -> new SliderControl(opt, 0, 9, 1, frames()))
                        .setBinding((opts, value) -> opts.advanced.cpuRenderAheadLimit = value, opts -> opts.advanced.cpuRenderAheadLimit)
                        .build()
                )
                .build());

        // Frame Trace: the chunk-draw instrumentation. It is not a performance option -- it measures
        // so that the performance work can be aimed at something real. Off by default; the switch
        // and its two knobs live here so it can be turned on from the game instead of from a
        // launcher's JVM arguments, which is not where a mod's options belong.
        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Diagnóstico"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Registo de frames (Frame Trace)"))
                        .setTooltip(Text.literal("Grava, um registo por frame em sodium-trace.csv na pasta do jogo, o que o caminho "
                                + "de renderização de chunks fez: regiões visitadas, sub-desenhos entregues ao driver antes e "
                                + "depois da fusão de intervalos contíguos, e a distribuição de sub-desenhos por região.\n\n"
                                + "Serve para apontar a otimização a um gargalo medido, não a um palpite. Não é uma opção de "
                                + "desempenho: os contadores e a escrita do CSV custam tempo enquanto está ligado. Deixa "
                                + "desligado fora de uma medição."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(
                                (opts, value) -> {
                                    opts.advanced.enableFrameTrace = value;
                                    applyFrameTrace(opts);
                                },
                                opts -> opts.advanced.enableFrameTrace)
                        .build()
                )
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.literal("Janela do registo"))
                        .setTooltip(Text.literal("Número de frames acumulados antes de escrever o ficheiro e de emitir a linha de "
                                + "resumo no log.\n\n"
                                + "Só é usado com o registo ligado. Uma janela curta mostra picos; uma janela longa dilui-os e "
                                + "dá uma média mais estável."))
                        .setControl(opt -> new SliderControl(opt, 60, 1200, 60, ControlValueFormatter.number()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(
                                (opts, value) -> {
                                    opts.advanced.frameTraceWindow = value;
                                    applyFrameTrace(opts);
                                },
                                opts -> opts.advanced.frameTraceWindow)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Registo: glFinish"))
                        .setTooltip(Text.literal("Espera pela GPU (glFinish) à volta de cada desenho de região, para que o tempo "
                                + "registado seja o de conclusão e não o de submissão.\n\n"
                                + "Isto serializa o pipeline e baixa a taxa de fotogramas de forma bem visível. Serve para uma "
                                + "medição pontual que separe tempo de CPU de tempo de GPU; nunca para avaliar desempenho.\n\n"
                                + "Só é usado com o registo ligado."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(
                                (opts, value) -> {
                                    opts.advanced.frameTraceGlFinish = value;
                                    applyFrameTrace(opts);
                                },
                                opts -> opts.advanced.frameTraceGlFinish)
                        .build()
                )
                .build());

        return new OptionPage(Text.literal("Avançado"), ImmutableList.copyOf(groups));
    }

    /**
     * Rows that do not make Sodium faster, but let the player stop paying for things they do not
     * need. They exist because the alternative was another mod doing the same with its own mixins
     * and its own screen; keeping them here means one config file and one place to read what each
     * switch actually changes.
     */
    public static OptionPage visual() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Céu e objetos"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Céu"))
                        .setTooltip(Text.literal("Desenha o céu da dimensão: o gradiente de fundo, o sol, a lua e as estrelas. "
                                + "Desligado, a passagem do céu é saltada inteira e o horizonte mostra a cor de fundo da "
                                + "dimensão, que é a mesma que a névoa usa.\n\n"
                                + "O que afeta: poucos quads por fotograma, por isso o ganho é pequeno; só compensa quando a "
                                + "câmara passa muito tempo a olhar para o horizonte, ou quando o limite já é o preenchimento. "
                                + "As nuvens são uma opção separada e continuam a ser desenhadas. Não altera a luz do mundo "
                                + "nem o ciclo do dia."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.visual.sky = value, opts -> opts.visual.sky)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Feixes de beacon"))
                        .setTooltip(Text.literal("Não desenha o cilindro de luz do beacon. O bloco, o vidro colorido e os efeitos "
                                + "continuam iguais: quem calcula o feixe e aplica os efeitos é o servidor, o cliente só desenha "
                                + "a coluna translúcida.\n\n"
                                + "O que afeta: cada feixe é um cilindro alto, translúcido, desenhado com mistura e sem escrever "
                                + "profundidade; é preenchimento e sobreposição puros. Numa base com muitos beacons o custo "
                                + "soma-se, e desligar remove-o por completo. Perdes a referência visual de onde estão os "
                                + "beacons, nada mais."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.visual.beaconBeams = value, opts -> opts.visual.beaconBeams)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Livro da mesa de encantamento"))
                        .setTooltip(Text.literal("Não desenha o livro aberto que flutua sobre a mesa de encantamento. A mesa, a "
                                + "interface e o encantamento continuam a funcionar.\n\n"
                                + "O que afeta: um modelo pequeno e animado por mesa, redesenhado em cada fotograma. O ganho só "
                                + "se nota em salas com muitas mesas, como as de mercadores de livros. É seguro porque o livro "
                                + "não mostra nada que a interface já não diga."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.visual.enchantingTableBook = value, opts -> opts.visual.enchantingTableBook)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Block entities"))
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.literal("Distância dos block entities"))
                        .setTooltip(Text.literal("Percentagem da distância de renderização até onde os block entities são desenhados. "
                                + "A 100% desenha tudo o que a distância de renderização alcança; a 50% desenha só até metade "
                                + "dela (por exemplo, 8 chunks numa distância de 16).\n\n"
                                + "O que afeta: o modelo animado que é desenhado por cima do bloco. Baús, camas, placas, banners, "
                                + "shulkers e crânios têm o modelo todo nesse renderizador e desaparecem; o feixe do beacon e o "
                                + "livro da mesa perdem só o acrescento. O bloco continua a existir e a funcionar; ao "
                                + "aproximares-te, volta a aparecer.\n\n"
                                + "Objetos desenhados pelo Vanillin (camas, fogueiras, pistões e molduras experimentais) não passam "
                                + "por aqui e continuam visíveis. Custa uma atualização do renderizador ao aplicar."))
                        .setControl(option -> new SliderControl(option, 25, 100, 5, value -> Text.literal(value + "%")))
                        .setBinding((opts, value) -> opts.visual.blockEntityDistance = value, opts -> opts.visual.blockEntityDistance)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .build());

        return new OptionPage(Text.literal("Visual"), ImmutableList.copyOf(groups));
    }

    /**
     * The colored-lighting page. It is separate from Visual because it is a replacement of the
     * light model, not a toggle of one object, and because both paths are experimental: the ESSL
     * file is handed to the driver as-is, so a driver that disagrees with it fails the shader, not
     * the option.
     */
    public static OptionPage experimental() {
        List<OptionGroup> groups = new ArrayList<>();
        boolean irisPack = ColoredLighting.isIrisShaderPackInUse();
        boolean irisInstalled = ColoredLighting.isIrisLoaded();
        String irisNote = irisPack ? "\n\nO Iris está a usar um shaderpack: as opções de iluminação ficam desligadas até o desativares." : "";
        String newIrisNote = irisInstalled
                ? "\n\nO Iris está instalado: as sombras e o reflexo do Sodium ficam desligados, porque o Iris controla o renderizador."
                : "";

        Option<SodiumGameOptions.LightingPath> lighting = OptionImpl.createBuilder(SodiumGameOptions.LightingPath.class, sodiumOpts)
                .setName(Text.literal("Sistema"))
                .setTooltip(Text.literal("Desligado desenha com o shader normal do Sodium. GLSL entrega ao MobileGlues um "
                        + "shader GLSL 330, que ele traduz para ESSL antes de o passar ao driver. ESSL entrega "
                        + "#version 320 es, que o MobileGlues reconhece como já traduzido e manda tal e qual ao "
                        + "driver, sem tradução. Os dois caminhos são opcionais e exclusivos: o controlo roda por "
                        + "Desligado, GLSL e ESSL.\n\n"
                        + "O que afeta: troca a rampa única do lightmap pela cor de cada fonte (tocha, alma, "
                        + "lava, portais, ametista, sculk, frogluz, velas e outras). O motor escolhido "
                        + "abaixo decide se essa cor vem de um atlas 2D ou de uma lista de uniformes; "
                        + "nenhum chunk é reconstruído. "
                        + "Com um shaderpack do Iris ativo fica desligado, porque o Iris substitui o shader do "
                        + "Sodium. Aplicar recarrega o renderizador." + irisNote))
                .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.LightingPath.class, new Text[] {
                        Text.literal("Desligado"), Text.literal("GLSL (MobileGlues)"), Text.literal("ESSL (direto)") }))
                .setBinding((opts, value) -> {
                    if (opts.experimental.lighting != value) {
                        ColoredLighting.resetShaderFailure();
                    }
                    opts.experimental.lighting = value;
                }, opts -> opts.experimental.lighting == null ? SodiumGameOptions.LightingPath.OFF : opts.experimental.lighting)
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setEnabled(!irisPack)
                .build();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Iluminação colorida"))
                .add(lighting)
                .add(OptionImpl.createBuilder(SodiumGameOptions.LightEngine.class, sodiumOpts)
                        .setName(Text.literal("Motor de Luz"))
                        .setTooltip(Text.literal("Escolhe como a luz colorida é calculada.\n\n"
                                + "Grelha: um atlas 2D de 32x16x32 blocos à volta do jogador, refeito só quando "
                                + "andas 8 blocos ou um bloco muda; o shader lê uma cor por posição. É o modo "
                                + "antigo, agora ancorado ao mundo (deixa de piscar).\n\n"
                                + "Analítico: não usa textura nenhuma. O jogo mantém uma lista das luzes mais "
                                + "próximas (até 32, ou 24/16 se o driver tiver poucos uniforms) e o shader soma "
                                + "cada uma com a mesma curva quadrática de 12 blocos da luz na mão. O chão fica "
                                + "com exatamente a mesma intensidade e cor da mão, e não há upload, logo não há "
                                + "piscar possível. As luzes fora do alcance da lista ficam com a rampa quente "
                                + "vanilla.\n\n"
                                + "Nos dois modos a cor só aparece onde existe luz de bloco vanilla, por isso não "
                                + "atravessa paredes nem acende cavernas escuras.\n\n"
                                + "O que afeta: o Analítico custa por vértice conforme o número de luzes e está "
                                + "limitado às mais próximas; a Grelha custa uma leitura fixa. Mudar de motor "
                                + "recarrega o renderizador."))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.LightEngine.class, new Text[] {
                                Text.literal("Grelha (atlas 2D)"), Text.literal("Analítico (uniforms)") }))
                        .setBinding((opts, value) -> {
                            if (opts.experimental.lightEngine != value) {
                                ColoredLighting.resetShaderFailure();
                            }
                            opts.experimental.lightEngine = value;
                        }, opts -> opts.experimental.lightEngine == null ? SodiumGameOptions.LightEngine.GRID : opts.experimental.lightEngine)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Luz na mão"))
                        .setTooltip(Text.literal("Soma a luz do bloco que o jogador segura na mão principal (ou na secundária, se a "
                                + "principal estiver vazia). A posição é a dos olhos, ligeiramente à frente; a atenuação é "
                                + "quadrática até 12 blocos e não usa a normal da face, porque o formato de vértice do "
                                + "Sodium não a transporta.\n\n"
                                + "O que afeta: poucas instruções por vértice e um uniform por frame. Só aparece quando o "
                                + "item segurado emite luz (tocha, lanterna, glowstone, lava). É independente do caminho "
                                + "GLSL/ESSL: com o sistema Desligado não faz nada."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.experimental.handLight = value, opts -> opts.experimental.handLight)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Luz vibrante"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Cor fiel no decaimento"))
                        .setTooltip(Text.literal(
                                "Onde o campo colorido fica fraco, a imagem atual mistura com a base quente vanilla "
                                        + "(laranja) e apaga a cor da fonte. Ligado, a matiz real da tocha, do alma, da "
                                        + "frogluz e afins aparece desde o primeiro bloco até ao fim do alcance.\n\n"
                                + "O que afeta: um uniform por frame no shader de chunks; não recarrega o shader nem "
                                        + "reconstrói a grelha."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.experimental.vibrantFalloff = value,
                                opts -> opts.experimental.vibrantFalloff)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Preservar cor no núcleo"))
                        .setTooltip(Text.literal(
                                "Aplica um ombro exponencial a partir de 80% do pico antes do clamp final. O núcleo "
                                        + "próximo à fonte deixava de saturar em branco e perdia a matiz; com o ombro, a cor "
                                        + "é comprimida e mantida perto de 1.0.\n\n"
                                + "O que afeta: um uniform por frame; não recarrega o shader. Só existe no caminho normal "
                                        + "da iluminação colorida: sem efeito com o perfil MakeUp No Effects ativo."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.experimental.vibrantToneMap = value,
                                opts -> opts.experimental.vibrantToneMap)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Pico de brilho"))
                        .setTooltip(Text.literal(
                                "Leva o pico da luz de bloco de 1.0 para 1.5, dando fôlego ao campo colorido (no Rethinking "
                                        + "Voxels as fontes chegam a 24). A grelha 8-bit guarda dividido por 1.5 e o shader "
                                        + "multiplica de volta, sincronizado com o upload.\n\n"
                                + "O que afeta: Java no cálculo da luz + um uniform por frame; marca a grelha para "
                                        + "reconstruir ao alternar. Combine com Preservar cor no núcleo para o núcleo não "
                                        + "saturar em branco."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.experimental.vibrantPeak = value;
                            ColoredLighting.markDirty();
                        }, opts -> opts.experimental.vibrantPeak)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Soma aditiva de luzes"))
                        .setTooltip(Text.literal(
                                "Em vez de mostrar apenas a fonte dominante em cada célula, soma as contribuições de todas "
                                        + "as fontes próximas (tocha + alma, lava + glowstone…), como no Rethinking Voxels. "
                                        + "Sobreposições misturam cor em vez de uma apagar a outra.\n\n"
                                + "O que afeta: Java no rebuild da grelha; reconstrói a grelha ao alternar. Somas acima de 1.0 "
                                        + "são recortadas no upload — combine com Pico de brilho e Preservar cor no núcleo."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.experimental.vibrantAdditive = value;
                            ColoredLighting.markDirty();
                        }, opts -> opts.experimental.vibrantAdditive)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Alcance de 16 blocos"))
                        .setTooltip(Text.literal(
                                "Passa o raio da luz de 12 para 16 blocos. A grelha, a lista analítica e a luz na mão usam "
                                        + "o mesmo raio, para não quebrar a invariante de curva compartilhada.\n\n"
                                + "O que afeta: Java no rebuild + um uniform por frame; reconstrói a grelha ao alternar. "
                                        + "Nota: a grelha continua 32×16×32, pelo que o alcance vertical fica limitado aos 8 "
                                        + "blocos acima/abaixo do jogador."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.experimental.vibrantRadius = value;
                            ColoredLighting.markDirty();
                        }, opts -> opts.experimental.vibrantRadius)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Paleta saturada"))
                        .setTooltip(Text.literal(
                                "Usa uma versão mais saturada da paleta de cores (frogluz, sea lantern, alma e afins), mais "
                                        + "próxima do Rethinking Voxels e menos pastel.\n\n"
                                + "O que afeta: mapa de cores em Java; reconstrói grelha e lista analítica ao alternar. A "
                                        + "luz na mão segue o mapa ao instante."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.experimental.vibrantPalette = value;
                            ColoredLighting.markDirty();
                        }, opts -> opts.experimental.vibrantPalette)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(!irisPack)
                        .build())
                .build());

        Option<Boolean> coloredShadows = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Sombras coloridas"))
                .setTooltip(Text.literal("Escurece e tinge a parte ambiente da luz onde a superfície está abrigada do céu e "
                        + "sem luz de bloco. A sombra aprofunda com a altura e com a falta de luz; a cor segue o "
                        + "lightmap e a luz colorida local, por isso uma tocha, lava ou portal tinge a sombra em "
                        + "vez de a deixar cinzenta.\n\n"
                        + "Como é barato: é calculada por vértice no shader de chunks, a partir do lightmap que o "
                        + "Sodium já tem. Não há shadow map, passagem extra, framebuffer nem textura nova. A luz de "
                        + "bloco continua a iluminar a superfície; só a contribuição do céu é escurecida.\n\n"
                        + "Só tem efeito com o Sistema de iluminação colorida ligado. Com um shaderpack do Iris "
                        + "ativo, ou com o Iris instalado, fica desligado." + newIrisNote))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.coloredShadows = value,
                        opts -> opts.experimental.coloredShadows)
                .setImpact(OptionImpact.LOW)
                .setEnabled(!irisInstalled)
                .build();

        BooleanSupplier shadowAvailable = () -> !irisInstalled
                && lighting.getValue() != SodiumGameOptions.LightingPath.OFF;
        BooleanSupplier shadowDetails = () -> shadowAvailable.getAsBoolean() && coloredShadows.getValue();

        Option<Integer> shadowStrength = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Força das sombras"))
                .setTooltip(Text.literal("Quanto a luz ambiente é escurecida nas zonas abrigadas. A 0% o efeito "
                        + "desaparece na prática; a 100% é o valor máximo, pensado para ser usado com a "
                        + "profundidade e a cor abaixo.\n\n"
                        + "É lido por frame, por isso o slider vale logo depois de Aplicar e não obriga a "
                        + "recarregar o renderizador."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.shadowStrength = value,
                        opts -> opts.experimental.shadowStrength)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> shadowDepth = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Profundidade das sombras"))
                .setTooltip(Text.literal("Quanto do efeito vem de estar fundo no mundo, além da oclusão do céu. A 0% "
                        + "as sombras dependem só da luz que chega à face; a 100% as superfícies baixas "
                        + "escurecem mais do que as altas, mesmo com a mesma luz.\n\n"
                        + "A altura é lida do mundo, não da câmara. É útil para dar peso a cavernas, ravinas e "
                        + "construções fundas sem tocar na luz de bloco."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.shadowDepth = value,
                        opts -> opts.experimental.shadowDepth)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> shadowTint = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Cor das sombras"))
                .setTooltip(Text.literal("Quanto a sombra segue a cor da luz colorida local. A 0% fica azul-acinzentada "
                        + "como a luz do céu; a 100% assume o tom da tocha, lava, portal ou outra fonte que "
                        + "esteja a iluminar a face.\n\n"
                        + "Sem luz colorida a sombra fica no tom do lightmap. Isto não acende nada: só mistura "
                        + "a cor da sombra, nunca aumenta a intensidade."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.shadowTint = value,
                        opts -> opts.experimental.shadowTint)
                .setImpact(OptionImpact.LOW)
                .build();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Sombras"))
                .add(coloredShadows)
                .add(new GatedOption<>(shadowStrength, shadowDetails))
                .add(new GatedOption<>(shadowDepth, shadowDetails))
                .add(new GatedOption<>(shadowTint, shadowDetails))
                .build());

        Option<Boolean> surfaceReflection = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Reflexo de superfície"))
                .setTooltip(Text.literal("Adiciona um brilho Fresnel do céu às superfícies opacas, incluindo a grama. "
                        + "É o efeito de reflexo do Sodium; não é um espelho e não replica água, vidro ou "
                        + "metal: é uma camada de céu nas faces vistas de raspão.\n\n"
                        + "Desligado, a grama e as restantes superfícies ficam foscas. É o padrão, porque o "
                        + "Sodium não tem reflexos especulares no renderizador normal e um brilho a mais era "
                        + "exatamente o que fazia a grama parecer vidro.\n\n"
                        + "Como é barato: o shader deriva uma normal de face da posição na tela, sem acrescentar "
                        + "normais ao formato de vértice nem fazer uma passagem extra. O código só é compilado "
                        + "quando ligas a opção. Precisa de recarregar o renderizador.\n\n"
                        + "Com o Iris instalado fica desligado, porque o Iris controla o shader do mundo." + newIrisNote))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.surfaceReflection = value,
                        opts -> opts.experimental.surfaceReflection)
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setEnabled(!irisInstalled)
                .build();

        BooleanSupplier reflectionDetails = () -> !irisInstalled && surfaceReflection.getValue();

        Option<Integer> reflectionStrength = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Intensidade do reflexo"))
                .setTooltip(Text.literal("Quanto do céu é misturado na superfície quando o ângulo é rasante. A 0% "
                        + "não se vê reflexo; a 100% é a mistura máxima. O valor é aplicado por frame, por isso "
                        + "mudar o slider não recompila o shader."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.reflectionStrength = value,
                        opts -> opts.experimental.reflectionStrength)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> reflectionSoftness = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Suavidade do reflexo"))
                .setTooltip(Text.literal("Controla o expoente Fresnel. A 0% o brilho espalha-se por mais ângulos e "
                        + "a superfície parece mais polida; a 100% fica concentrado nas faces vistas de "
                        + "raspão, como uma superfície mais fosca.\n\n"
                        + "É um uniform lido por frame; não obriga a recarregar o renderizador."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.reflectionSoftness = value,
                        opts -> opts.experimental.reflectionSoftness)
                .setImpact(OptionImpact.LOW)
                .build();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("Reflexos"))
                .add(surfaceReflection)
                .add(new GatedOption<>(reflectionStrength, reflectionDetails))
                .add(new GatedOption<>(reflectionSoftness, reflectionDetails))
                .build());

        Option<Boolean> profileNoEffects = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Perfil MakeUp No Effects"))
                .setTooltip(Text.literal("Reproduz o perfil No Effects do MakeUp dentro do shader de chunks do Sodium, sem "
                        + "correr o shaderpack. Mantém o céu e a névoa próprios, a água com absorção 0.10 e "
                        + "reflexo do céu, as ondas da água, o brilho nocturno 0.60, a luz mínima de caverna "
                        + "0.030, a temperatura da luz de bloco 1, a luz na mão e as nuvens vanilla. O perfil "
                        + "não inclui sombras, SSR, AO, AA, DoF, motion blur, bloom, aberração cromática, "
                        + "godrays, movimento das folhas, nuvens volumétricas, reflexo das nuvens, brilho dos "
                        + "materiais, refração nem brilho do sol/lua. As sombras coloridas continuam a ser "
                        + "uma opção à parte, mas o modelo do perfil não tem sombras: com o perfil ligado, "
                        + "essa opção não altera o terreno. O reflexo de superfície do Sodium, se estiver "
                        + "ligado, continua a ser aplicado nas passagens opacas.\n\n"
                        + "O que afeta: só o terreno, a água, o céu e a névoa. Entidades, mão e partículas "
                        + "continuam com o sombreado vanilla/Sodium. No Nether e no End o perfil fica "
                        + "desligado e o Sodium usa o caminho normal, com o céu e a névoa vanilla. Funciona "
                        + "com a iluminação colorida ligada ou desligada: com ela "
                        + "ligada, a luz de bloco usa as cores do Sodium; desligada, usa a paleta de vela do "
                        + "MakeUp. Não é um shaderpack: não há shadow map, framebuffer extra, depth texture "
                        + "nem cópia da cena.\n\n"
                        + "Com o Iris instalado fica desligado, porque o Iris controla o shader do mundo. "
                        + "Ligar ou desligar recarrega o renderizador; os restantes controlos são uniforms "
                        + "lidos por frame." + newIrisNote))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupNoEffects = value,
                        opts -> opts.experimental.makeupNoEffects)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setEnabled(!irisInstalled)
                .build();

        BooleanSupplier profileOn = () -> !irisInstalled && profileNoEffects.getValue();

        Option<Boolean> profileSky = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Céu do perfil"))
                .setTooltip(Text.literal("Desenha o gradiente do MakeUp, o sol, a lua, as estrelas e o brilho do "
                        + "poente num único triângulo de ecrã inteiro. Usa a mesma paleta do terreno, por isso "
                        + "o céu e o chão não ficam com cores diferentes ao pôr do sol. Só no Overworld.\n\n"
                        + "O que afeta: substitui o céu vanilla; as nuvens continuam as vanilla. Não mexe no "
                        + "terreno nem nos chunks."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupSky = value,
                        opts -> opts.experimental.makeupSky)
                .setImpact(OptionImpact.LOW)
                .setEnabled(!irisInstalled)
                .build();

        Option<Boolean> profileFog = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Névoa do perfil"))
                .setTooltip(Text.literal("Usa a curva de névoa do MakeUp, com a cor do horizonte em vez da cor da "
                        + "cena. A intensidade sobe com a luz do céu e com a chuva; à noite e em cavernas "
                        + "fecha menos. A distância é a distância de renderização atual.\n\n"
                        + "O que afeta: só o terreno e a água. Não há cópia da cena, por isso a cor é uma "
                        + "aproximação do horizonte e não a cor desfocada por trás da água. Só no Overworld."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupFog = value,
                        opts -> opts.experimental.makeupFog)
                .setImpact(OptionImpact.LOW)
                .setEnabled(!irisInstalled)
                .build();

        Option<Boolean> profileWater = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Água e vidro"))
                .setTooltip(Text.literal("Liga os efeitos de água do perfil: a tinta de absorção, as ondas e o "
                        + "reflexo do céu. O reflexo também aparece no vidro, no gelo e nos restantes blocos "
                        + "translúcidos, porque partilham a mesma passagem; a Absorção e as Ondas são só para "
                        + "a água.\n\n"
                        + "O que afeta: um bit novo no material do vértice, lido só por este shader. Não há "
                        + "refração nem espelho da cena; o reflexo é a cor do céu com Fresnel. Desligado, a "
                        + "água e o vidro ficam com o sombreado normal do Sodium."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupWater = value,
                        opts -> opts.experimental.makeupWater)
                .setImpact(OptionImpact.LOW)
                .setEnabled(!irisInstalled)
                .build();

        BooleanSupplier waterOn = () -> profileOn.getAsBoolean() && profileWater.getValue();

        Option<Integer> profileWaterAbsorption = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Absorção da água"))
                .setTooltip(Text.literal("Quanto a água absorve a luz. O perfil usa 0.10; a 0.00 a tinta "
                        + "desaparece e ficam só as ondas e o reflexo. Como o Sodium não tem depth texture "
                        + "nem cópia da cena nesta passagem, o valor é aplicado por ângulo: quanto mais "
                        + "rasante a vista, mais água o raio atravessa e mais forte é a tinta.\n\n"
                        + "O que afeta: só as faces de água. É um uniform por frame; não recarrega o shader."))
                .setControl(option -> new SliderControl(option, 0, 80, 1,
                        value -> Text.literal(String.format(java.util.Locale.ROOT, "%.2f", value / 100.0F))))
                .setBinding((opts, value) -> opts.experimental.makeupWaterAbsorption = value,
                        opts -> opts.experimental.makeupWaterAbsorption)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Boolean> profileWaterReflection = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Reflexo do céu na água/vidro"))
                .setTooltip(Text.literal("Mistura a cor do céu na água e no vidro com Fresnel. O perfil mantém "
                        + "este reflexo, mas desliga o brilho do sol/lua: não há glint nem espelho da cena.\n\n"
                        + "O que afeta: um uniform por frame. O céu refletido é o gradiente do horizonte e do "
                        + "zénite, não uma segunda passagem da cena."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupWaterSkyReflection = value,
                        opts -> opts.experimental.makeupWaterSkyReflection)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Boolean> profileWaterWaves = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Ondas da água"))
                .setTooltip(Text.literal("Perturba a normal da superfície da água com duas senoides ancoradas ao "
                        + "mundo. Não desloca vértices: a água continua plana, só a luz e o reflexo variam.\n\n"
                        + "O que afeta: algumas instruções por fragmento de água. O valor do perfil é 0.9, "
                        + "ajustável em baixo."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupWaterWaves = value,
                        opts -> opts.experimental.makeupWaterWaves)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> profileWaterWaveStrength = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Força das ondas"))
                .setTooltip(Text.literal("Escala a perturbação da normal. A 0% as ondas desaparecem; a 100% é "
                        + "uma ondulação forte. O perfil usa 90%.\n\n"
                        + "O que afeta: um uniform por frame; não recarrega o shader."))
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.makeupWaterWaveStrength = value,
                        opts -> opts.experimental.makeupWaterWaveStrength)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> profileNightBrightness = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Brilho nocturno"))
                .setTooltip(Text.literal("Quanto o céu e a luz da noite iluminam o terreno. O perfil usa 0.60; "
                        + "a fase da lua ainda multiplica este valor, como no MakeUp.\n\n"
                        + "O que afeta: a paleta do céu, a luz ambiente e a névoa. É um uniform por frame."))
                .setControl(option -> new SliderControl(option, 5, 70, 5, value -> Text.literal(value + "%")))
                .setBinding((opts, value) -> opts.experimental.makeupNightBrightness = value,
                        opts -> opts.experimental.makeupNightBrightness)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> profileMinCaveLight = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Luz mínima em cavernas"))
                .setTooltip(Text.literal("Chão mínimo da luz omni do perfil. O valor do MakeUp é 0.030: uma "
                        + "caverna sem tochas não fica preta, fica com um azul muito escuro. A 0.000 fica "
                        + "preta; acima de 0.060 perde o contraste das cavernas.\n\n"
                        + "O que afeta: só a luz ambiente; não acende a luz de bloco nem atravessa paredes. "
                        + "É um uniform por frame."))
                .setControl(option -> new SliderControl(option, 0, 60, 5,
                        value -> Text.literal(String.format(java.util.Locale.ROOT, "%.3f", value / 1000.0F))))
                .setBinding((opts, value) -> opts.experimental.makeupMinCaveLight = value,
                        opts -> opts.experimental.makeupMinCaveLight)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Integer> profileBlockLightTemp = OptionImpl.createBuilder(int.class, sodiumOpts)
                .setName(Text.literal("Temperatura da luz de bloco"))
                .setTooltip(Text.literal("Cor base da luz de vela/tocha no perfil. O MakeUp usa 1 (âmbar). "
                        + "Quente puxa para laranja, Neutro para amarelo, Frio para branco-azulado e Branco "
                        + "para cinzento.\n\n"
                        + "O que afeta: a paleta da luz de bloco quando a iluminação colorida do Sodium está "
                        + "desligada; com ela ligada, as cores das fontes continuam a mandar. É um uniform "
                        + "por frame."))
                .setControl(option -> new SliderControl(option, 0, 4, 1, value -> Text.literal(
                        new String[] { "Quente", "Âmbar", "Neutro", "Frio", "Branco" }[value])))
                .setBinding((opts, value) -> opts.experimental.makeupBlockLightTemp = value,
                        opts -> opts.experimental.makeupBlockLightTemp)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Boolean> profileSunLight = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Sol direcional (N·L)"))
                .setTooltip(Text.literal("Liga o sol do perfil sem shadow map: a face virada ao sol recebe a cor "
                        + "directa, a face oposta perde-a. O termo só chega a superfícies que vêem o céu, com "
                        + "a rampa ^10 do MakeUp; é isso que escurece telhados, cavernas e paredes viradas "
                        + "para norte sem uma sombra desenhada.\n\n"
                        + "O que afeta: um uniform por frame. Desligado, o terreno fica só com a luz omni e a "
                        + "luz de bloco."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.makeupSunLight = value,
                        opts -> opts.experimental.makeupSunLight)
                .setImpact(OptionImpact.LOW)
                .build();

        Option<Boolean> profileHandLight = OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setName(Text.literal("Luz na mão no perfil"))
                .setTooltip(Text.literal("Soma a luz do bloco segurado, com a mesma curva do MakeUp: a tocha na "
                        + "mão ilumina o terreno à frente com a mesma intensidade de uma tocha colocada. Esta "
                        + "opção é a mesma que a Luz na mão da iluminação colorida; o valor fica partilhado.\n\n"
                        + "O que afeta: poucas instruções por vértice e um uniform por frame."))
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> opts.experimental.handLight = value,
                        opts -> opts.experimental.handLight)
                .setImpact(OptionImpact.LOW)
                .build();

        groups.add(OptionGroup.createBuilder()
                .setName(Text.literal("MakeUp No Effects"))
                .add(profileNoEffects)
                .add(new GatedOption<>(profileSky, profileOn))
                .add(new GatedOption<>(profileFog, profileOn))
                .add(new GatedOption<>(profileSunLight, profileOn))
                .add(new GatedOption<>(profileHandLight, profileOn))
                .add(new GatedOption<>(profileWater, profileOn))
                .add(new GatedOption<>(profileWaterAbsorption, waterOn))
                .add(new GatedOption<>(profileWaterReflection, waterOn))
                .add(new GatedOption<>(profileWaterWaves, waterOn))
                .add(new GatedOption<>(profileWaterWaveStrength, waterOn))
                .add(new GatedOption<>(profileNightBrightness, profileOn))
                .add(new GatedOption<>(profileMinCaveLight, profileOn))
                .add(new GatedOption<>(profileBlockLightTemp, profileOn))
                .build());

        return new OptionPage(Text.literal("Experimental"), ImmutableList.copyOf(groups));
    }

    /**
     * Pushes the three Frame Trace switches into the instrument. Each binding writes its own field
     * first, so re-reading all three here keeps the others where they were and the change lands
     * without a restart.
     */
    private static void applyFrameTrace(SodiumGameOptions opts) {
        FrameTrace.configure(opts.advanced.enableFrameTrace, opts.advanced.frameTraceWindow,
                opts.advanced.frameTraceGlFinish);
    }

    // ------------------------------------------------------------- value labels

    /** "12 chunks", rather than the vanilla "%s Chunks" with its capital. */
    private static ControlValueFormatter chunks() {
        return value -> Text.literal(value + " chunks");
    }

    private static ControlValueFormatter guiScale() {
        return value -> value == 0 ? Text.literal("Automático") : Text.literal(value + "x");
    }

    private static ControlValueFormatter brightness() {
        return value -> {
            if (value == 0) {
                return Text.literal("Escuro");
            }
            if (value == 100) {
                return Text.literal("Claro");
            }
            return Text.literal(value + "%");
        };
    }

    private static ControlValueFormatter fpsLimit() {
        return value -> value == 260 ? Text.literal("Sem limite") : Text.literal(value + " fps");
    }

    private static ControlValueFormatter biomeBlend() {
        return value -> Text.literal(value + (value == 1 ? " bloco" : " blocos"));
    }

    /** The chunk builder's 0 means "let Sodium pick", which is not the same as "no threads". */
    private static ControlValueFormatter threads() {
        return value -> value == 0 ? Text.literal("Automático") : Text.literal(value + " threads");
    }

    private static ControlValueFormatter frames() {
        return value -> value == 0 ? Text.literal("Sincronizado") : Text.literal(value + (value == 1 ? " frame" : " frames"));
    }
}
