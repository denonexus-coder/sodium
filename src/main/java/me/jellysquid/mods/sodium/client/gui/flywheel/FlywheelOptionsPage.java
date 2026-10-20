package me.jellysquid.mods.sodium.client.gui.flywheel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.google.common.collect.ImmutableList;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Flywheel's configuration, as a page of Sodium's options screen.
 *
 * <p>The whole of {@code flywheel.json} is reachable from here: the master switch, the two engine
 * knobs, the three instancing knobs and the three effect switches. Each row says what the value
 * changes inside the engine and what it costs, rather than restating its own name.
 *
 * <p>Everything below the master switch is wrapped in a {@link GatedOption} whose gate reads the
 * master's current, not yet applied value. That is what makes the page react while it is still
 * being edited: untick Flywheel and the rest of the page greys out immediately, before Apply is
 * pressed, and tick it again and everything comes back with the choices it had.
 *
 * <p>The flags are set from where the value is read in the backend, not by taste. An option
 * consulted while the engine is built carries {@link OptionFlag#REQUIRES_RENDERER_RELOAD}; one read
 * per frame, per upload or per particle carries nothing and starts working on the next frame; one
 * whose worker pool is created once for the life of the process carries
 * {@link OptionFlag#REQUIRES_GAME_RESTART}, because applying it would otherwise look like it did
 * nothing.
 */
public final class FlywheelOptionsPage {
	private FlywheelOptionsPage() {
	}

	/**
	 * Builds the page, or returns {@code null} when Flywheel is not installed so the menu does not
	 * grow an empty tab.
	 */
	@Nullable
	public static OptionPage create() {
		if (!FlywheelCompat.PRESENT) {
			return null;
		}

		List<OptionGroup> groups = new ArrayList<>();

		// Built first because every other row asks it whether it is on. getValue() is the value
		// currently shown on screen, not the applied one, so the gating follows the user's edit.
		Option<Boolean> master = OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Flywheel ligado"))
				.setTooltip(Text.literal(
						"Liga o motor de instanciação. Cada tipo de objeto que o Vanillin instancia — baús, "
								+ "sinos, camas, pistões, itens, carrinhos, displays — passa a ser escrito num "
								+ "buffer de instâncias e desenhado numa chamada por modelo, em vez de uma por "
								+ "objeto.\n\n"
								+ "O que afeta: geometria, animação e luz desses objetos. O renderizador vanilla "
								+ "do mesmo objeto deixa de correr, para não desenhar duas vezes.\n\n"
								+ "Desligar escreve flywheel:off no flywheel.json e devolve tudo ao caminho "
								+ "vanilla, com uma chamada de desenho por objeto. O backend é escolhido quando "
								+ "o motor é construído, por isso precisa de recarregar o renderizador."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.enabled(value), data -> FlywheelCompat.enabled())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();

		groups.add(OptionGroup.createBuilder()
				.setName(Text.literal("Estado"))
				.add(master)
				.add(statusRow())
				.build());

		BooleanSupplier engine = master::getValue;

		groups.add(OptionGroup.createBuilder()
				.setName(Text.literal("Motor"))
				.add(gated(workerThreadsOption(), engine))
				.add(gated(limitUpdatesOption(), engine))
				.build());

		var instancingGroup = OptionGroup.createBuilder()
				.setName(Text.literal("Instanciação"))
				.add(gated(lightSmoothnessOption(), engine))
				.add(gated(orphanUploadOption(), engine));

		// Only when this Flywheel build carries the flag. The rest of the page works against
		// older builds, so the row is simply left out rather than shown doing nothing.
		if (FlywheelCompat.hasHideOffscreen()) {
			instancingGroup.add(gated(hideOffscreenOption(), engine));
		}

		groups.add(instancingGroup.build());

		groups.add(OptionGroup.createBuilder()
				.setName(Text.literal("Efeitos"))
				.add(gated(oitOption(), engine))
				.add(gated(disableParticlesOption(), engine))
				.add(gated(freezeGuiOption(), engine))
				.build());

		return new OptionPage(Text.literal("Flywheel"), ImmutableList.copyOf(groups));
	}

	// ------------------------------------------------------------------ engine

	/**
	 * Shader precompilation threads. {@code -1} hands the choice to Flywheel, {@code 0} keeps
	 * compilation on the render thread. The pool is created once and cached, so applying this one
	 * needs a restart rather than a reload.
	 */
	private static Option<Integer> workerThreadsOption() {
		return OptionImpl.createBuilder(int.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Threads de compilação de shaders"))
				.setTooltip(Text.literal(
						"Quantas threads pré-compilam os shaders da instanciação. São compilados uma vez, no "
								+ "arranque e quando o tipo de luz muda, não a cada frame.\n\n"
								+ "−1: o Flywheel escolhe. 0: compila na thread de render — o jogo congela "
								+ "durante a compilação, mas não há contenção. N: usa N threads; termina mais "
								+ "depressa, mas compete com a construção de chunks e com o render.\n\n"
								+ "O que afeta: só o tempo e o pico de CPU do arranque, não o custo por frame. O "
								+ "pool é criado uma vez por processo, por isso só vale no arranque seguinte."))
				.setControl(option -> new SliderControl(option, FlywheelCompat.workerThreadsMin(),
						FlywheelCompat.workerThreadsMax(), 1, value -> {
							if (value < 0) {
								return Text.literal("Automático");
							}
							if (value == 0) {
								return Text.literal("Thread de render");
							}
							return Text.literal(Integer.toString(value));
						}))
				.setImpact(OptionImpact.LOW)
				.setBinding((data, value) -> FlywheelCompat.workerThreads(value), data -> FlywheelCompat.workerThreads())
				.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
				.build();
	}

	/**
	 * Whether distant objects are still updated every frame. On by default, and the switch behind
	 * one of the larger CPU wins in a build with a lot of machinery in the world.
	 */
	private static Option<Boolean> limitUpdatesOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Limitar atualizações à distância"))
				.setTooltip(Text.literal(
						"Objetos longe da câmara deixam de ser atualizados em todos os frames: entram num "
								+ "escalonamento por bandas, cada banda um subconjunto de frames escolhido por um "
								+ "número primo. Quanto mais longe, menor a fração de frames em que são visitados.\n\n"
								+ "O que afeta: o custo por frame de um campo denso de block entities, que é quase "
								+ "todo atualização de matriz e não desenho. O que está perto continua a atualizar "
								+ "sempre.\n\n"
								+ "Efeito visível: animações distantes (tampa a abrir, item a rodar) podem andar a "
								+ "um ritmo menor. Num objeto estático não se nota. Precisa de recarregar o "
								+ "renderizador."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> FlywheelCompat.limitUpdates(value), data -> FlywheelCompat.limitUpdates())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	// -------------------------------------------------------------- instancing

	/**
	 * How light is read across a face. Part of the shader key, so a change recompiles the pipelines
	 * and is worth flagging as a reload.
	 */
	private static Option<FlywheelLightSmoothness> lightSmoothnessOption() {
		return OptionImpl.createBuilder(FlywheelLightSmoothness.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Suavidade da luz"))
				.setTooltip(Text.literal(
						"Como a luz é lida dentro de cada face. É uma constante de compilação, por isso cada "
								+ "valor recompila os pipelines.\n\n"
								+ "Plano: um valor por bloco, sem interpolação. O mais barato, e vê-se o degrau.\n\n"
								+ "Trilinear: interpola as 8 esquinas à volta do fragmento. Suaviza os degraus, mas "
								+ "não calcula oclusão ambiente — o AO fica a 1.\n\n"
								+ "Suave: lê a vizinhança 3×3×3 e reproduz o cálculo de luz e AO do Minecraft. O "
								+ "mais fiel e o mais caro.\n\n"
								+ "Suave + face interior: igual, mais a correção das faces viradas para dentro de um "
								+ "canto, que sem ela apanham a luz da face errada.\n\n"
								+ "O que afeta: só a cor final dos fragmentos instanciados, não a luz das chunks."))
				.setControl(option -> new CyclingControl<>(option, FlywheelLightSmoothness.class, new Text[]{
						Text.literal("Plano"),
						Text.literal("Trilinear"),
						Text.literal("Suave"),
						Text.literal("Suave + face interior"),
				}))
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.lightSmoothness(value.name()),
						data -> FlywheelLightSmoothness.fromConfig())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	/**
	 * Read inside the instance upload itself rather than when the backend is built, so it takes
	 * effect on the next frame of a running world and needs no reload at all.
	 */
	private static Option<Boolean> orphanUploadOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Upload orfanado de instâncias"))
				.setTooltip(Text.literal(
						"Em vez de escrever por cima do buffer que a GPU pode ainda estar a ler "
								+ "(glBufferSubData), aloca um buffer novo com o array inteiro (glBufferData) e "
								+ "deixa o antigo retirar-se em segundo plano.\n\n"
								+ "Porquê: uma escrita por cima numa região em leitura tem de ser ordenada contra "
								+ "ela. Num GPU tile-based o driver resolve isso drenando a fila, e o CPU fica "
								+ "parado à espera da GPU. A realocação evita a espera.\n\n"
								+ "Só quando o driver é móvel e o buffer não precisa de crescer. Medido nesta GPU "
								+ "(PowerVR GE8320, 2500 instâncias): ~0,6 ms por frame, contra 2,0–3,0 ms com "
								+ "atualização parcial. Custo: reescreve o array inteiro.\n\n"
								+ "É lido em cada upload, por isso vale no frame seguinte."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.orphanInstanceUpload(value),
						data -> FlywheelCompat.orphanInstanceUpload())
				.build();
	}

	/**
	 * Hide instances outside the view frustum so they are pulled out of the instancer instead of
	 * merely having their transform left un-written.
	 *
	 * <p>The distinction is the whole point of the switch. The visuals have always tested the
	 * frustum, but all that ever did was skip a matrix update, which leaves the instance in the
	 * draw: it still costs a slot in every instanced call and still gets rewritten into the
	 * instance array each frame. Hiding it removes it from the instancer outright.
	 *
	 * <p>No reload flag: the frustum is tested again every frame, so an instance revealed by
	 * turning this off comes back on the next frame of a running world.
	 */
	private static Option<Boolean> hideOffscreenOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Esconder instâncias fora do ecrã"))
				.setTooltip(Text.literal(
						"Fora do frustum, a instância passa a Hidden e sai do instancer, em vez de ficar no "
								+ "desenho com a matriz por escrever.\n\n"
								+ "A diferença é o que se poupa: um teste que só evita a escrita da matriz continua "
								+ "a ocupar um slot em cada chamada instanciada e continua a ser reescrito no array "
								+ "em todos os frames. Escondida não custa nem uma coisa nem outra, e sai do "
								+ "trabalho de animação.\n\n"
								+ "O que afeta: só o que está fora do campo de visão; o que está à frente fica igual. "
								+ "A decisão é reconciliada a cada frame, por isso uma instância escondida por "
								+ "engano volta assim que o frustum a incluir.\n\n"
								+ "Vale no frame seguinte."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.hideOffscreenInstances(value),
						data -> FlywheelCompat.hideOffscreenInstances())
				.build();
	}

	// -------------------------------------------------- transparency and effects

	/**
	 * The framebuffer for order-independent transparency is allocated when the instancing engine is
	 * constructed, so this one has to flag a reload rather than pretend to apply on the spot.
	 */
	private static Option<Boolean> oitOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Transparência independente da ordem (OIT)"))
				.setTooltip(Text.literal(
						"Desenha os translúcidos instanciados sem depender da ordem: guarda as contribuições "
								+ "num framebuffer e resolve-as no fim.\n\n"
								+ "O que afeta: objetos instanciados com material translúcido. Sem isto, dois que "
								+ "se cruzam podem ficar com a mistura errada, e o erro muda conforme a câmara "
								+ "roda.\n\n"
								+ "Custo: um framebuffer a mais e uma passagem de resolução por frame. Numa GPU "
								+ "tile-based isso pesa em largura de banda. Desligar poupa essa largura ao preço "
								+ "dos artefactos.\n\n"
								+ "O framebuffer pertence ao renderizador, por isso precisa de recarregar."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.oit(value), data -> FlywheelCompat.oit())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	private static Option<Boolean> disableParticlesOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Desativar todas as partículas"))
				.setTooltip(Text.literal(
						"Corta as partículas no momento em que são criadas: o ponto de corte é "
								+ "ParticleEngine.add, o funil por onde passa toda a partícula, do jogo e de "
								+ "outros mods.\n\n"
								+ "O que afeta: fumo, fogo, brasas, poção, explosões, blocos quebrados, rastos — "
								+ "tudo. Poupa a simulação de cada partícula e o overdraw, que num telemóvel é "
								+ "caro com fumo ou chuva no ecrã.\n\n"
								+ "O custo é perder o feedback visual: não se vê um bloco a arder nem uma poção a "
								+ "fazer efeito. É a opção mais agressiva da página. Vale no frame seguinte."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> FlywheelCompat.disableParticles(value),
						data -> FlywheelCompat.disableParticles())
				.build();
	}

	private static Option<Boolean> freezeGuiOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Congelar animação dos atlas com um menu aberto"))
				.setTooltip(Text.literal(
						"Com um ecrã aberto (inventário, opções, baú), a animação dos atlas pára de avançar e "
								+ "de reenviar frames para a GPU.\n\n"
								+ "O que afeta: todas as texturas animadas, não só os itens do inventário — a água "
								+ "e o fogo atrás do menu também param. Com o mundo à vista nada muda.\n\n"
								+ "Porque poupa: cada frame animado é um upload de textura, e com um menu aberto o "
								+ "mundo não é o foco. Vale no frame seguinte."))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.LOW)
				.setBinding((data, value) -> FlywheelCompat.freezeGuiItemAnimation(value),
						data -> FlywheelCompat.freezeGuiItemAnimation())
				.build();
	}

	// -------------------------------------------------------------------- status

	/**
	 * Shown, never switched. The driver is identified once from the GL strings, so this row is
	 * built as a tick box that refuses input: it reports what was detected instead of offering a
	 * change that would be written nowhere.
	 */
	private static Option<Boolean> statusRow() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.literal("Driver móvel detetado"))
				.setTooltip(Text.literal(
						"Informativo, não é um interruptor: é lido das strings do OpenGL uma vez, no "
								+ "arranque.\n\n"
								+ "É verdadeiro quando o driver se identifica como móvel ou como camada de "
								+ "tradução — neste dispositivo, MobileGlues sobre PowerVR — e é isso que liga os "
								+ "caminhos que só existem para GPUs tile-based, como o upload orfanado.\n\n"
								+ "Se estiver falso num dispositivo que devia ser móvel, o Flywheel está a tomar os "
								+ "caminhos de desktop e as opções móveis não têm efeito."))
				.setControl(TickBoxControl::new)
				.setEnabled(false)
				.setBinding((data, value) -> {
				}, data -> FlywheelCompat.mobileDriver())
				.build();
	}

	private static <T> Option<T> gated(Option<T> option, BooleanSupplier gate) {
		return new GatedOption<>(option, gate);
	}
}
