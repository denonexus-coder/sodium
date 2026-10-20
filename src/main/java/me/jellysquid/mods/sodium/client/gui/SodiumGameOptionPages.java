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

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
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
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
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
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.literal("Brilho"))
                        .setTooltip(Text.literal("Multiplica o brilho final da imagem — o gamma do jogo. Não altera a luz calculada "
                                + "no mundo: só levanta o que já foi calculado, por isso não revela nada numa caverna sem luz, "
                                + "apenas torna o escuro menos escuro.\n\n"
                                + "O que afeta: só a cor final dos píxeis. Custo zero por frame. Vale no frame seguinte."))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, brightness()))
                        .setBinding((opts, value) -> opts.getGamma().setValue(value * 0.01D), (opts) -> (int) (opts.getGamma().getValue() / 0.01D))
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
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

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
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
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
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
