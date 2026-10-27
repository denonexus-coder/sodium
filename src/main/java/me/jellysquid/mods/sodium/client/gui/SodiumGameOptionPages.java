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

// TODO: Rename in Sodium 0.6
public class SodiumGameOptionPages {
    private static final SodiumOptionsStorage sodiumOpts = new SodiumOptionsStorage();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.renderDistance"))
                        .setTooltip(Text.translatable("sodium.options.view_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, 32, 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.getViewDistance().setValue(value), options -> options.getViewDistance().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.simulationDistance"))
                        .setTooltip(Text.translatable("sodium.options.simulation_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 5, 32, 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.getSimulationDistance().setValue(value), options -> options.getSimulationDistance().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.gamma"))
                        .setTooltip(Text.translatable("sodium.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.getGamma().setValue(value * 0.01D), (opts) -> (int) (opts.getGamma().getValue() / 0.01D))
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.guiScale"))
                        .setTooltip(Text.translatable("sodium.options.gui_scale.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, MinecraftClient.getInstance().getWindow().calculateScaleFactor(0, MinecraftClient.getInstance().forcesUnicodeFont()), 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, value) -> {
                            opts.getGuiScale().setValue(value);

                            MinecraftClient client = MinecraftClient.getInstance();
                            client.onResolutionChanged();
                        }, opts -> opts.getGuiScale().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.fullscreen"))
                        .setTooltip(Text.translatable("sodium.options.fullscreen.tooltip"))
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
                        .setName(Text.translatable("options.vsync"))
                        .setTooltip(Text.translatable("sodium.options.v_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getEnableVsync()))
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.framerateLimit"))
                        .setTooltip(Text.translatable("sodium.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, 260, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> {
                            opts.getMaxFps().setValue(value);
                            MinecraftClient.getInstance().getWindow().setFramerateLimit(value);
                        }, opts -> opts.getMaxFps().getValue())
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.viewBobbing"))
                        .setTooltip(Text.translatable("sodium.options.view_bobbing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getBobView()))
                        .build())
                .add(OptionImpl.createBuilder(AttackIndicator.class, vanillaOpts)
                        .setName(Text.translatable("options.attackIndicator"))
                        .setTooltip(Text.translatable("sodium.options.attack_indicator.tooltip"))
                        .setControl(opts -> new CyclingControl<>(opts, AttackIndicator.class, new Text[] { Text.translatable("options.off"), Text.translatable("options.attack.crosshair"), Text.translatable("options.attack.hotbar") }))
                        .setBinding((opts, value) -> opts.getAttackIndicator().setValue(value), (opts) -> opts.getAttackIndicator().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.autosaveIndicator"))
                        .setTooltip(Text.translatable("sodium.options.autosave_indicator.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getShowAutosaveIndicator().setValue(value), opts -> opts.getShowAutosaveIndicator().getValue())
                        .build())
                .build());

        return new OptionPage(Text.translatable("stat.generalButton"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
                        .setName(Text.translatable("options.graphics"))
                        .setTooltip(Text.translatable("sodium.options.graphics_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, GraphicsMode.class, new Text[] { Text.translatable("options.graphics.fast"), Text.translatable("options.graphics.fancy"), Text.translatable("options.graphics.fabulous") }))
                        .setBinding(
                                (opts, value) -> opts.getGraphicsMode().setValue(value),
                                opts -> opts.getGraphicsMode().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(CloudRenderMode.class, vanillaOpts)
                        .setName(Text.translatable("options.renderClouds"))
                        .setTooltip(Text.translatable("sodium.options.clouds_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, CloudRenderMode.class, new Text[] { Text.translatable("options.off"), Text.translatable("options.graphics.fast"), Text.translatable("options.graphics.fancy") }))
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
                        .setName(Text.translatable("soundCategory.weather"))
                        .setTooltip(Text.translatable("sodium.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.leaves_quality.name"))
                        .setTooltip(Text.translatable("sodium.options.leaves_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(ParticlesMode.class, vanillaOpts)
                        .setName(Text.translatable("options.particles"))
                        .setTooltip(Text.translatable("sodium.options.particle_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, ParticlesMode.class, new Text[] { Text.translatable("options.particles.all"), Text.translatable("options.particles.decreased"), Text.translatable("options.particles.minimal") }))
                        .setBinding((opts, value) -> opts.getParticles().setValue(value), (opts) -> opts.getParticles().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.ao"))
                        .setTooltip(Text.translatable("sodium.options.smooth_lighting.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getAo().setValue(value), opts -> opts.getAo().getValue())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.biomeBlendRadius"))
                        .setTooltip(Text.translatable("sodium.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 7, 1, ControlValueFormatter.biomeBlend()))
                        .setBinding((opts, value) -> opts.getBiomeBlendRadius().setValue(value), opts -> opts.getBiomeBlendRadius().getValue())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.entityDistanceScaling"))
                        .setTooltip(Text.translatable("sodium.options.entity_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 50, 500, 25, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.getEntityDistanceScaling().setValue(value / 100.0), opts -> Math.round(opts.getEntityDistanceScaling().getValue().floatValue() * 100.0F))
                        .setImpact(OptionImpact.HIGH)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.entityShadows"))
                        .setTooltip(Text.translatable("sodium.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getEntityShadows().setValue(value), opts -> opts.getEntityShadows().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.vignette.name"))
                        .setTooltip(Text.translatable("sodium.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .build())
                .build());


        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.mipmapLevels"))
                        .setTooltip(Text.translatable("sodium.options.mipmap_levels.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.getMipmapLevels().setValue(value), opts -> opts.getMipmapLevels().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .build());


        return new OptionPage(Text.translatable("sodium.options.pages.quality"), ImmutableList.copyOf(groups));
    }

    public static OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.chunk_update_threads.name"))
                        .setTooltip(Text.translatable("sodium.options.chunk_update_threads.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, Runtime.getRuntime().availableProcessors(), 1, ControlValueFormatter.quantityOrDisabled("threads", "Default")))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.chunkBuilderThreads = value, opts -> opts.performance.chunkBuilderThreads)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.always_defer_chunk_updates.name"))
                        .setTooltip(Text.translatable("sodium.options.always_defer_chunk_updates.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.alwaysDeferChunkUpdates = value, opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .build()
        );

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_block_face_culling.name"))
                        .setTooltip(Text.translatable("sodium.options.use_block_face_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useBlockFaceCulling = value, opts -> opts.performance.useBlockFaceCulling)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Multi-Region Coalescing"))
                        .setTooltip(Text.literal("Preenche primeiro (CPU) todas as regiões visíveis e só depois despacha os draws (GPU), em duas fases separadas, com batches reutilizáveis por região.\n\n"
                                + "Evita que o driver seja forçado a fazer flush a meio do preenchimento, um custo alto em arquiteturas TBDR (PowerVR GE8320).\n\n"
                                + "O que afeta: a forma de submissão do terreno. Desligado, cada região é preenchida e desenhada de imediato, exatamente como sempre."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.useMultiRegionCoalescing = value, opts -> opts.performance.useMultiRegionCoalescing)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_fog_occlusion.name"))
                        .setTooltip(Text.translatable("sodium.options.use_fog_occlusion.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.performance.useFogOcclusion = value, opts -> opts.performance.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_entity_culling.name"))
                        .setTooltip(Text.translatable("sodium.options.use_entity_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useEntityCulling = value, opts -> opts.performance.useEntityCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.animate_only_visible_textures.name"))
                        .setTooltip(Text.translatable("sodium.options.animate_only_visible_textures.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.animateOnlyVisibleTextures = value, opts -> opts.performance.animateOnlyVisibleTextures)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_no_error_context.name"))
                        .setTooltip(Text.translatable("sodium.options.use_no_error_context.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.useNoErrorGLContext = value, opts -> opts.performance.useNoErrorGLContext)
                        .setEnabled(supportsNoErrorContext())
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .build());

        // Interruptores A/B das otimizações. Todos são lidos no caminho quente a cada frame/job,
        // por isso não pedem reinício nem recarregamento: o valor novo vale no frame seguinte.
        // (Staging COHERENT é a exceção -- o buffer é criado uma vez, logo pede recarregar o
        // renderer, que continua a não reiniciar o jogo.)
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Fundir sub-draws contíguas"))
                        .setTooltip(Text.literal("Agrupa sub-draws consecutivas que leem o mesmo buffer de índices numa só chamada de draw. Medido: cada sub-draw custa ~2.2 us de CPU no PowerVR, tempo que volta direto ao frame.\n\nO que afeta: número de chamadas ao driver por frame. Sem efeito visual (cull de faces ativo)."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.mergeSubDraws = value, opts -> opts.performance.mergeSubDraws)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Alargar caras visíveis"))
                        .setTooltip(Text.literal("Junta as faces visíveis de cada secção num intervalo contíguo, para que a fusão de sub-draws consiga dobrá-las. Custo: triângulos de costas que o driver descarta depois do vertex shader (só quando o cull de faces está ligado).\n\nO que afeta: mais triângulos submetidos, menos chamadas de draw. Sem mudança de pixels com cull ligado."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.widenVisibleFaces = value, opts -> opts.performance.widenVisibleFaces)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Staging COHERENT"))
                        .setTooltip(Text.literal("Buffer de staging com PERSISTENT+COHERENT em vez de PERSISTENT+CLIENT_STORAGE. Medido no PowerVR GE8320 contra libmobileglues: mediana 0.255 ms contra 0.310 ms (1.22x mais rápido).\n\nO que afeta: upload de vértices de chunks. Como o buffer só é criado com o renderer, pede recarregar o renderer ao aplicar -- não reinicia o jogo."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.coherentStaging = value, opts -> opts.performance.coherentStaging)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Saltar vêinhos iguais na fatia"))
                        .setTooltip(Text.literal("O WorldSlice não volta a desempacotar as secções vizinhas que não mudaram (teste de identidade exato): cerca de metade das ~8000 escritas (32 KB) que cada job de meshing pagava.\n\nO que afeta: tráfego de memória nas threads de construção de chunks. Os dados ficam idênticos; uma secção alterada é sempre um objeto novo e é sempre copiada."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.skipNeighborUnpacks = value, opts -> opts.performance.skipNeighborUnpacks)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Cache de luz com geração"))
                        .setTooltip(Text.literal("Substitui o Arrays.fill de 8000 inteiros (32 KB) que cada rebuild fazia para rearmar a cache de luz por um carimbo de geração O(1).\n\nO que afeta: alocação e limpeza de memória por job de construção. O resultado calculado é o mesmo."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.lightCacheGenStamp = value, opts -> opts.performance.lightCacheGenStamp)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Evitar reenvio de uniformes"))
                        .setTooltip(Text.literal("Não reenvia para a GPU um uniforme cujo valor não mudou desde a última vez (matrizes de câmara, samplers, encolhimento de UV, névoa).\n\nO que afeta: número de chamadas glUniform* por passe. Nada muda no ecrã: o valor guardado é exatamente o que seria enviado."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.uniformValueCache = value, opts -> opts.performance.uniformValueCache)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Ordenar sub-draws por vértice"))
                        .setTooltip(Text.literal("Ordena os comandos de desenho por offset de vértice antes da fusão, para que as corridas contíguas se encontrem e se dobrem o máximo possível.\n\nNOVO -- por medir, começa desligado. Não toca nas passes translúcidas, onde a ordem de pintura tem de ser preservada."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.sortSubDraws = value, opts -> opts.performance.sortSubDraws)
                        .build())
                .build());

        return new OptionPage(Text.translatable("sodium.options.pages.performance"), ImmutableList.copyOf(groups));
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
                        .setName(Text.translatable("sodium.options.use_persistent_mapping.name"))
                        .setTooltip(Text.translatable("sodium.options.use_persistent_mapping.tooltip"))
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
                        .setName(Text.translatable("sodium.options.cpu_render_ahead_limit.name"))
                        .setTooltip(Text.translatable("sodium.options.cpu_render_ahead_limit.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 9, 1, ControlValueFormatter.translateVariable("sodium.options.cpu_render_ahead_limit.value")))
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
                        .setName(Text.translatable("sodium.options.frame_trace.name"))
                        .setTooltip(Text.translatable("sodium.options.frame_trace.tooltip"))
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
                        .setName(Text.translatable("sodium.options.frame_trace_window.name"))
                        .setTooltip(Text.translatable("sodium.options.frame_trace_window.tooltip"))
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
                        .setName(Text.translatable("sodium.options.frame_trace_gl_finish.name"))
                        .setTooltip(Text.translatable("sodium.options.frame_trace_gl_finish.tooltip"))
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

        return new OptionPage(Text.translatable("sodium.options.pages.advanced"), ImmutableList.copyOf(groups));
    }

    /**
     * INFINITIUM LIGHT ENGINE -- um unico motor de iluminacao (interruptor mestre) com
     * sub-opcoes por sector (S1..S10). Todas as opcoes sao lidas ao vivo no caminho quente:
     * ligar ou desligar qualquer uma faz efeito no proximo frame/tick, sem reiniciar o jogo.
     */
    public static OptionPage infinitium() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("INFINITIUM LIGHT ENGINE"))
                        .setTooltip(Text.literal("Interruptor mestre do motor de iluminacao voxel.\n\n"
                                + "Hibrido: a luz vanilla continua a dar niveis, oclusao e construcao de chunks; o ILE "
                                + "propaga apenas a COR RGB numa grelha propria a volta do jogador e aplica-a a malha por vertice.\n\n"
                                + "Um unico caminho, com as sub-opcoes abaixo. Desligado, o shader de chunks e identico ao do Sodium normal."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.infinitium.enabled = value,
                                opts -> opts.infinitium.enabled)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.InfinitiumGridSize.class, sodiumOpts)
                        .setName(Text.literal("Tamanho da grelha"))
                        .setTooltip(Text.literal("Quantos blocos a volta do jogador a grelha RGB cobre.\n\n"
                                + "Pequena: 24x12x24 (mais barata). Media: 32x16x32 (padrao). Grande: 48x24x48 (mais alcance, "
                                + "raio de luz 14).\n\n"
                                + "O que afeta: memoria e custo do rebuild (que so corre quando o jogador atravessa 8 blocos ou muda um bloco)."))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.InfinitiumGridSize.class))
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.infinitium.gridSize = value,
                                opts -> opts.infinitium.gridSize)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.literal("Forca da cor"))
                        .setTooltip(Text.literal("Quanto a cor do ILE tinge a luz existente (0 a 100%).\n\n"
                                + "Em 100% uma tocha tinge as paredes de laranja-quente; em 0% a luz fica vanilla. A intensidade "
                                + "e a oclusao continuam sempre da luz vanilla."))
                        .setControl(option -> new SliderControl(option, 0, 100, 5, ControlValueFormatter.percentage()))
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.infinitium.tintStrength = value,
                                opts -> opts.infinitium.tintStrength)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Oclusao por paredes (S2/S3)"))
                        .setTooltip(Text.literal("A cor RGB so se propaga por celulas que a luz de bloco vanilla alcancou.\n\n"
                                + "Paredes opacas, portas fechadas e agua comportam-se exatamente como o jogo: a luz nao "
                                + "atravessa obstaculos e cada material tem a translucidez que o vanilla lhe da.\n\n"
                                + "Desligado, a cor espalha-se em esfera (atravessa paredes) -- e o modo de comparacao."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.infinitium.respectOcclusion = value,
                                opts -> opts.infinitium.respectOcclusion)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Atenuacao suave (S5)"))
                        .setTooltip(Text.literal("Curva de queda da cor por distancia: 1/(1+k d²).\n\n"
                                + "A cor nao desaparece de forma abrupta ao afastar -- sobe e cai progressivamente. "
                                + "Desligado usa a curva classica (1 - d²/R²)², que corta a zero exatamente no raio."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.infinitium.smoothAttenuation = value,
                                opts -> opts.infinitium.smoothAttenuation)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Mistura estavel (S5/S6)"))
                        .setTooltip(Text.literal("As contribuicoes das fontes sao combinadas como media ponderada normalizada: "
                                + "duas tochas de cores diferentes nao explodem em saturacao e a cor de cada uma mantem a "
                                + "identidade onde domina.\n\n"
                                + "Desligado, as cores sao somadas em bruto com clamp -- o 'efeito discoteca' que se quer poder comparar."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.infinitium.stableColorMix = value,
                                opts -> opts.infinitium.stableColorMix)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Filtragem temporal (S6)"))
                        .setTooltip(Text.literal("Suaviza oscilacoes entre reconstruicoes sem atrasar nada importante: "
                                + "aumentos (acender uma tocha) passam de imediato; quedas (apagar) descem amortecidas "
                                + "em cerca de 4 reconstruicoes.\n\n"
                                + "O que afeta: estabilidade da cor a andar e a colocar/retirar fontes."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.infinitium.temporalFilter = value,
                                opts -> opts.infinitium.temporalFilter)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Luz na mao (S8)"))
                        .setTooltip(Text.literal("O item luminoso na mao ilumina a volta como fonte dinamica.\n\n"
                                + "E atualizada por frame como uma fonte móvel propria: andar com uma tocha na mao nao "
                                + "reconstrói a grelha a cada passo. Balde de lava conta."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.infinitium.handLight = value,
                                opts -> opts.infinitium.handLight)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Prioridade a luz proxima (S3/S10)"))
                        .setTooltip(Text.literal("Classes de luz cujas fontes estao la fora de 24 blocos sao processadas por "
                                + "ultimo e com metade do raio: a luz proxima nunca espera pela distante.\n\n"
                                + "O que afeta: ordem e custo do rebuild (a fisica da luz nao muda, so a ordem do trabalho)."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.infinitium.nearPriority = value,
                                opts -> opts.infinitium.nearPriority)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Brilho de minérios (S1)"))
                        .setTooltip(Text.literal("Blocos que o vanilla deixa a zero (minérios, metais, cristais) passam a "
                                + "emitir um brilho fraco da sua cor mineral.\n\n"
                                + "E uma tabela de dados extra no mesmo scan -- sem passe, textura ou uniform novo. "
                                + "Ligado so, aumenta o numero de fontes."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.infinitium.emissiveBlocks = value,
                                opts -> opts.infinitium.emissiveBlocks)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.literal("Estatisticas (S10)"))
                        .setTooltip(Text.literal("Regista no log o custo real de cada reconstruicao do ILE: tempo em ms, "
                                + "classes de cor, fontes e celulas iluminadas.\n\n"
                                + "Uma linha a cada 2 segundos enquanto ligado."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.infinitium.debugStats = value,
                                opts -> opts.infinitium.debugStats)
                        .build())
                .build());

        return new OptionPage(Text.literal("Infinitium Light Engine"), ImmutableList.copyOf(groups));
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
}
