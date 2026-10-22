package me.jellysquid.mods.sodium.client.gui.vanillin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Vanillin's configuration, as a page of Sodium's options screen.
 *
 * <p>Vanillin decides which vanilla objects the Flywheel engine instances instead of vanilla
 * rendering them. Every visual it knows about appears here as a three-state control, enumerated
 * from Vanillin's own registry rather than a hardcoded list, so the page cannot go stale against
 * the mod it configures.
 *
 * <p>The three states are not two settings wearing a costume. {@code Default} hands the decision
 * back to Vanillin, which turns on what it ships as stable and leaves the experimental visuals off
 * in a production environment. {@code Disable} removes the visualizer entirely, so the object goes
 * down the vanilla path exactly as if Vanillin were not installed. {@code Force enable} installs
 * one even where a mod has asked Vanillin to leave that object alone.
 *
 * <p>Apply pushes the new state into the registry and then reloads the renderer, which rebuilds
 * every visual from it: the chunk sections are recompiled, and each one re-adds the block entities
 * it contains, while the fresh visualization manager re-adds the entities from the level. The
 * change therefore lands in-game, at the cost of a chunk rebuild rather than a restart.
 *
 * <p>The names and descriptions are literal Portuguese keyed by registry name, with the
 * registry-derived label as the fallback for a visual another mod registered. Each description
 * says what the instanced version replaces, what it does not reproduce, and what changes when the
 * row is switched back to vanilla.
 */
public final class VanillinOptionsPage {
	private VanillinOptionsPage() {
	}

	/** Name and description per registry key, so the page does not depend on a lang file. */
	private record Info(String name, String description) {
	}

	private static final String FALLBACK_DESCRIPTION =
			"Visual registado por outro mod através do configurador do Vanillin. O Sodium não sabe o que "
					+ "ele desenha além do nome, por isso só o pode ligar ou desligar; o efeito exato está "
					+ "no mod que o registou.\n\n"
					+ "Desligar remove o visualizador e devolve o objeto ao renderizador vanilla.";

	private static final Map<String, Info> INFO = Map.ofEntries(
			// ---------------------------------------------------------- block entities
			Map.entry("minecraft:chest", new Info("Baú",
					"Base, tampa e fecho são uma árvore de instâncias; a abertura é uma rotação escrita "
							+ "nessa árvore. O renderizador vanilla do baú deixa de correr.\n\n"
							+ "Desligar devolve o baú ao vanilla: uma chamada de desenho por baú. Com muitos "
							+ "baús seguidos, é aí que se nota.")),
			Map.entry("minecraft:ender_chest", new Info("Baú do End",
					"Mesma árvore do baú comum, mas o baú do End não tem animação de tampa: a tampa é uma "
							+ "instância fixa e o resto nunca é reescrito.\n\n"
							+ "Desligar devolve ao vanilla. Com um baú isolado não se nota diferença; com uma "
							+ "sala cheia, nota-se.")),
			Map.entry("minecraft:trapped_chest", new Info("Baú armadilha",
					"Como o baú comum, com a textura de armadilha; a tampa abre e fecha pela mesma "
							+ "rotação.\n\n"
							+ "O sinal de redstone não é tocado: só o desenho. Desligar devolve ao "
							+ "ChestRenderer.")),
			Map.entry("minecraft:bell", new Info("Sino",
					"Corpo e badalo são uma árvore de instâncias; o balanço é uma rotação aplicada à raiz, "
							+ "alimentada pelo estado de oscilação do bloco.\n\n"
							+ "O som e a física do sino continuam no servidor. Desligar devolve ao "
							+ "BellRenderer.")),
			Map.entry("minecraft:shulker_box", new Info("Caixa de shulker",
					"Caixa e tampa são duas instâncias; abrir é uma translação da tampa na árvore. A cor "
							+ "vem da textura da variante, escolhida quando o visual é criado.\n\n"
							+ "A caixa continua a abrir e a fechar no estado do bloco. Desligar devolve ao "
							+ "ShulkerBoxRenderer.")),
			Map.entry("minecraft:bed", new Info("Cama",
					"Cabeceira e pés são duas block entities separadas: cada metade instancia só a sua "
							+ "parte, com a cor do corante vinda da block entity e a luz tirada da metade mais "
							+ "iluminada, como no vanilla.\n\n"
							+ "Desligar devolve as duas metades ao BedRenderer. Dormir não muda.")),
			Map.entry("minecraft:lectern", new Info("Estante (lectern)",
					"Só o livro é desenhado pelo renderizador da estante; o corpo é malha de chunk e não é "
							+ "afetado. Instanciar substitui o livro — capa, páginas e a pose de livro aberto.\n\n"
							+ "Uma estante sem livro não tem nada para instanciar e fica no vanilla. Desligar "
							+ "devolve o livro ao LecternRenderer.")),
			Map.entry("minecraft:campfire", new Info("Fogueira",
					"Uma instância por slot de cozedura, quatro no total; um slot vazio fica escondido. A "
							+ "lenha e a chama são o modelo do bloco, e o fumo e as faíscas são partículas — "
							+ "nada disso é afetado.\n\n"
							+ "Desligar devolve os quatro itens ao CampfireRenderer. Cozinhar continua igual.")),
			Map.entry("minecraft:decorated_pot", new Info("Vaso decorado",
					"Uma base de tijolo e quatro lados, cada lado recortado para uma peça só e retexturado "
							+ "com o padrão que lhe está atribuído. Trocar um lado reconstrói só essa árvore; "
							+ "os outros três não são tocados.\n\n"
							+ "Um padrão desconhecido desenha a face sem nada, como no vanilla. Desligar devolve "
							+ "ao DecoratedPotRenderer.")),
			Map.entry("minecraft:piston", new Info("Pistão em movimento",
					"O bloco em movimento tem duas instâncias: a cabeça (ou o bloco empurrado) e o corpo do "
							+ "pistão, que só aparece atrás de um pistão de origem totalmente recolhido. As três "
							+ "variantes do PistonHeadRenderer são reproduzidas por frame a partir do progresso.\n\n"
							+ "O pistão continua a empurrar blocos da mesma maneira. Desligar devolve ao "
							+ "PistonHeadRenderer.")),
			Map.entry("minecraft:structure_block", new Info("Structure block",
					"As linhas de contorno são geometria de linha instanciada, com as cores por eixo do "
							+ "vanilla — cada eixo mistura dois valores.\n\n"
							+ "O volume é reescaneado no máximo uma vez por segundo; o vanilla faz em todos os "
							+ "frames. Uma alteração dentro da região pode demorar até 1 s a aparecer.\n\n"
							+ "Só é visível em criativo. Desligar devolve ao StructureBlockRenderer.")),

			// ---------------------------------------------------------------- entities
			Map.entry("minecraft:block_display", new Info("Display de bloco",
					"A entidade é instanciada com o modelo do bloco exibido, incluindo a transformação da "
							+ "entidade: posição, rotação, escala e interpolação aplicadas por frame.\n\n"
							+ "Um bloco sem modelo não tem geometria para instanciar. Desligar devolve ao "
							+ "BlockDisplayRenderer.")),
			Map.entry("minecraft:item_display", new Info("Display de item",
					"Como o display de bloco, mas o modelo vem do item e o contexto de exibição é o da "
							+ "entidade. A transformação da entidade é aplicada por frame.\n\n"
							+ "Só é instanciado quando o item já tem modelo; até lá quem desenha é o vanilla, "
							+ "para nunca haver dois desenhos nem nenhum. Desligar devolve ao "
							+ "ItemDisplayRenderer.")),
			Map.entry("minecraft:item_frame", new Info("Moldura",
					"A moldura e o item dentro dela são duas instâncias, e a rotação do item acompanha a da "
							+ "moldura. A moldura vazia é só a primeira.\n\n"
							+ "Desligar devolve ao ItemFrameRenderer. A rotação e a comparação de itens "
							+ "continuam iguais.")),
			Map.entry("minecraft:glow_item_frame", new Info("Moldura luminosa",
					"Moldura e item instanciados como na moldura normal; o brilho vem da textura, não de luz "
							+ "calculada, por isso não muda com a iluminação do local.\n\n"
							+ "Desligar devolve ao ItemFrameRenderer, que usa a mesma classe com a flag de "
							+ "brilho.")),
			Map.entry("minecraft:item", new Info("Item no chão",
					"Itens largados são instanciados com o modelo do item, com a rotação e o balanço do "
							+ "vanilla. O número de instâncias acompanha a quantidade do stack: um stack de 64 "
							+ "desenha várias cópias, como no vanilla.\n\n"
							+ "A física e o empilhamento não são tocados. Desligar devolve ao "
							+ "ItemEntityRenderer.")),
			Map.entry("minecraft:minecart", new Info("Carrinho de mina",
					"O corpo e o bloco ou item que transporta são instanciados, com a rotação do "
							+ "carrinho.\n\n"
							+ "Os trilhos e o movimento continuam iguais. Desligar devolve ao "
							+ "MinecartEntityRenderer.")),
			Map.entry("minecraft:chest_minecart", new Info("Carrinho com baú",
					"Corpo do carrinho e modelo de baú, ambos instanciados. O baú é desenhado fechado — um "
							+ "carrinho com baú não abre a tampa.\n\n"
							+ "Desligar devolve ao MinecartEntityRenderer.")),
			Map.entry("minecraft:command_block_minecart", new Info("Carrinho com bloco de comandos",
					"Corpo e modelo do bloco de comandos instanciados. O bloco não anima; a árvore é "
							+ "estática e só é reconstruída se o modelo mudar.\n\n"
							+ "Desligar devolve ao MinecartEntityRenderer.")),
			Map.entry("minecraft:furnace_minecart", new Info("Carrinho com forno",
					"Corpo e modelo de forno instanciados. O forno de um carrinho usa sempre o estado "
							+ "apagado, por isso a geometria é estática e não há reconstrução por tick.\n\n"
							+ "Desligar devolve ao MinecartEntityRenderer.")),
			Map.entry("minecraft:hopper_minecart", new Info("Carrinho com funil",
					"Corpo e modelo de funil instanciados. A geometria é estática.\n\n"
							+ "Desligar devolve ao MinecartEntityRenderer.")),
			Map.entry("minecraft:spawner_minecart", new Info("Carrinho com spawner",
					"Corpo e modelo de spawner instanciados.\n\n"
							+ "O mob a rodar dentro do spawner faz parte do renderizador do bloco e não é "
							+ "reproduzido — mas o vanilla também não o mostra num carrinho, porque desenha o "
							+ "bloco como modelo, não como block entity. Desligar devolve ao "
							+ "MinecartEntityRenderer.")),
			Map.entry("minecraft:tnt_minecart", new Info("Carrinho com TNT",
					"Corpo do carrinho e modelo de TNT instanciados. A TNT de um carrinho não pisca: o estado "
							+ "é fixo e a árvore também.\n\n"
							+ "Desligar devolve ao MinecartEntityRenderer.")),
			Map.entry("minecraft:falling_block", new Info("Bloco a cair",
					"O bloco a cair é uma instância do modelo do bloco, deslocada meio bloco como no "
							+ "vanilla.\n\n"
							+ "Só é instanciado quando o bloco já não ocupa a própria célula no nível; no "
							+ "primeiro frame quem desenha é o vanilla. O mesmo teste desliga o renderizador "
							+ "vanilla, por isso há sempre exatamente um a desenhar — nunca dois, nunca "
							+ "nenhum.\n\n"
							+ "Desligar devolve ao FallingBlockRenderer.")),
			Map.entry("minecraft:tnt", new Info("TNT acesa",
					"Uma instância do modelo da TNT. O rastilho é reproduzido: a escala sobe até 1,3× nos "
							+ "últimos dez ticks e a sobreposição branca alterna a cada cinco ticks.\n\n"
							+ "A explosão em si é do servidor e não é tocada. Desligar devolve ao "
							+ "TntRenderer.")),
			Map.entry("minecraft:boat", new Info("Barco",
					"Barco instanciado por variante de madeira, com os remos a remexer.\n\n"
							+ "A máscara de água por baixo do casco não é reproduzida: é um render type só de "
							+ "posições, que o Flywheel não consegue exprimir, e o vanilla desenhava-a duas "
							+ "vezes de qualquer forma. A água deixa de ser recortada à volta do casco.\n\n"
							+ "Desligar devolve ao BoatRenderer e a máscara volta."))
	);

	/**
	 * Builds the page, or returns {@code null} when Vanillin is not installed so the menu does not
	 * grow a tab full of rows that cannot be reached.
	 */
	@Nullable
	public static OptionPage create() {
		if (!VanillinCompat.PRESENT) {
			return null;
		}

		List<Option<VanillinState>> blockEntities = new ArrayList<>();
		for (VanillinCompat.Entry entry : VanillinCompat.blockEntityEntries()) {
			blockEntities.add(visualOption(entry));
		}

		List<Option<VanillinState>> entities = new ArrayList<>();
		for (VanillinCompat.Entry entry : VanillinCompat.entityEntries()) {
			entities.add(visualOption(entry));
		}

		// The bulk row looks at the same option objects as the rows below it, not at the saved
		// maps. That way it switches to "Misto" as soon as one row has an unsaved edit, and a
		// click on another bulk state is never mistaken for a no-op because of a stale snapshot.
		List<Option<VanillinState>> visuals = new ArrayList<>(blockEntities);
		visuals.addAll(entities);

		List<OptionGroup> groups = new ArrayList<>();

		groups.add(OptionGroup.createBuilder()
				.setName(Text.literal("Estado"))
				.add(introRow())
				.add(bulkRow(visuals))
				.build());

		addGroup(groups, blockEntities, Text.literal("Block entities"));
		addGroup(groups, entities, Text.literal("Entidades"));

		return new OptionPage(Text.literal("Vanillin"), ImmutableList.copyOf(groups));
	}

	/**
	 * Wraps a list of options in a group, skipping it when empty. A group with no options renders
	 * as a bare heading with nothing under it, which reads as a bug rather than as an absence.
	 */
	private static void addGroup(List<OptionGroup> groups, List<? extends Option<?>> options, Text name) {
		if (options.isEmpty()) {
			return;
		}

		OptionGroup.Builder builder = OptionGroup.createBuilder()
				.setName(name);
		for (Option<?> option : options) {
			builder.add(option);
		}

		groups.add(builder.build());
	}

	/**
	 * Shown, never switched. Vanillin has no master switch -- each visual stands on its own -- so
	 * this row reports how many visuals were found instead of pretending to gate rows behind
	 * nothing. The bulk row below is the only page-wide control.
	 */
	private static Option<Boolean> introRow() {
		return OptionImpl.createBuilder(boolean.class, VanillinOptionsStorage.INSTANCE)
				.setName(Text.literal(VanillinCompat.entries().size() + " visuais detetados"))
				.setTooltip(Text.literal(
						"Liga e desliga cada visual registado pelo Vanillin. Cada linha é um tipo de objeto e "
								+ "Aplicar instala a escolha no registo vivo e grava-a no vanillin.json.\n\n"
								+ "Padrão: o Vanillin decide — estáveis ligados, experimentais desligados fora de "
								+ "um ambiente de desenvolvimento.\n"
								+ "Vanilla: remove o visualizador; o objeto volta ao renderizador vanilla, como se o "
								+ "Vanillin não existisse.\n"
								+ "Forçar: instala um visualizador mesmo que outro mod tenha pedido para não o "
								+ "fazer.\n\n"
								+ "A linha «Todos os visuais» aplica um destes estados a todas as linhas de uma vez; "
								+ "«Misto» aparece quando elas não estão de acordo.\n\n"
								+ "O que o Aplicar faz: instala a escolha no registo de visualizadores e recarrega o "
								+ "renderizador. O mundo reconstrói as secções de chunk, e é isso que recria os "
								+ "visuais com a escolha nova — sem sair do jogo. Custa alguns segundos de chunks a "
								+ "reconstruir, e é esse o preço de não reiniciar."))
				.setControl(TickBoxControl::new)
				.setEnabled(false)
				.setBinding((data, value) -> {
				}, data -> !VanillinCompat.entries().isEmpty())
				.build();
	}

	/**
	 * One control for every row. It writes only when Aplicar runs, like every other option, and
	 * the per-visual rows come after it in the group list: a bulk change establishes the baseline
	 * and any row the player also edited still wins.
	 */
	private static Option<VanillinBulkState> bulkRow(List<Option<VanillinState>> visuals) {
		return new BulkOption(visuals);
	}

	/**
	 * The page-wide control. It is implemented here rather than as an {@link OptionImpl} because
	 * its value is not a stored field: it is the state of the rows below it, including edits that
	 * have not been applied yet. Reading those rows keeps the row on "Misto" while they disagree
	 * and makes selecting a state always count as a change, even when that state was also the
	 * value when the screen opened.
	 */
	private static final class BulkOption implements Option<VanillinBulkState> {
		private static final Text NAME = Text.literal("Todos os visuais");
		private static final Text TOOLTIP = Text.literal(
				"Aplica um estado a todas as linhas desta página de uma vez.\n\n"
						+ "Padrão: todos voltam à decisão do Vanillin — estáveis ligados, experimentais "
						+ "desligados fora de desenvolvimento.\n"
						+ "Vanilla: remove todos os visualizadores; cada objeto volta ao renderizador vanilla.\n"
						+ "Forçar instância: instala todos, incluindo os experimentais.\n"
						+ "Misto: não é uma escolha; aparece quando as linhas já não estão todas no mesmo "
						+ "estado. Escolher Misto não altera nada.\n\n"
						+ "As linhas individuais continuam a mandar: se também alterares uma delas, a escolha "
						+ "dessa linha prevalece sobre a escolha global.\n\n"
						+ "Aplicar grava o resultado no vanillin.json e recarrega o renderizador para os "
						+ "objetos que já estão no ecrã passarem a usar a escolha nova.");

		private final List<Option<VanillinState>> visuals;
		private final Control<VanillinBulkState> control;
		private VanillinBulkState pending;

		private BulkOption(List<Option<VanillinState>> visuals) {
			this.visuals = visuals;
			this.control = new CyclingControl<>(this, VanillinBulkState.class, new Text[]{
					Text.literal("Padrão"),
					Text.literal("Vanilla"),
					Text.literal("Forçar"),
					Text.literal("Misto")
			});
		}

		@Override
		public Text getName() {
			return NAME;
		}

		@Override
		public Text getTooltip() {
			return TOOLTIP;
		}

		@Override
		public OptionImpact getImpact() {
			return OptionImpact.MEDIUM;
		}

		@Override
		public Control<VanillinBulkState> getControl() {
			return this.control;
		}

		@Override
		public VanillinBulkState getValue() {
			if (this.pending != null) {
				return this.pending;
			}

			if (this.visuals.isEmpty()) {
				return VanillinBulkState.MIXED;
			}

			VanillinState first = this.visuals.get(0).getValue();
			if (first == null) {
				return VanillinBulkState.MIXED;
			}

			for (int i = 1; i < this.visuals.size(); i++) {
				if (this.visuals.get(i).getValue() != first) {
					return VanillinBulkState.MIXED;
				}
			}

			return switch (first) {
				case DEFAULT -> VanillinBulkState.DEFAULT;
				case DISABLE -> VanillinBulkState.DISABLE;
				case FORCE_ENABLE -> VanillinBulkState.FORCE_ENABLE;
			};
		}

		@Override
		public void setValue(VanillinBulkState value) {
			this.pending = value;
		}

		@Override
		public void reset() {
			this.pending = null;
		}

		@Override
		public OptionStorage<?> getStorage() {
			return VanillinOptionsStorage.INSTANCE;
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public boolean hasChanged() {
			return this.pending != null && this.pending != VanillinBulkState.MIXED;
		}

		@Override
		public void applyChanges() {
			VanillinBulkState value = this.pending;
			this.pending = null;

			if (value == null || value == VanillinBulkState.MIXED) {
				return;
			}

			VanillinState state = value.toState();
			VanillinCompat.setAll(state);

			for (Option<VanillinState> visual : this.visuals) {
				if (!visual.hasChanged()) {
					// Mark untouched rows with the value now in the map so they repaint. A row the
					// player edited keeps its own pending value and is applied after this option,
					// which is the documented precedence.
					visual.setValue(state);
				}
			}
		}

		@Override
		public Collection<OptionFlag> getFlags() {
			return Collections.emptyList();
		}
	}

	/**
	 * One visual, as a three-state control.
	 *
	 * <p>The name comes from {@link #INFO} and falls back to the registry-derived label for a visual
	 * another mod registered. The tooltip carries the three things a label cannot: what the instanced
	 * version replaces, what it does not reproduce, and whether Vanillin turns it on when the file
	 * has no opinion.
	 */
	private static Option<VanillinState> visualOption(VanillinCompat.Entry entry) {
		Info info = INFO.get(entry.key());

		return OptionImpl.createBuilder(VanillinState.class, VanillinOptionsStorage.INSTANCE)
				.setName(Text.literal(info != null ? info.name() : entry.label()))
				.setTooltip(tooltip(entry, info))
				.setControl(option -> new CyclingControl<>(option, VanillinState.class, new Text[]{
						Text.literal("Padrão"),
						Text.literal("Vanilla"),
						Text.literal("Forçar instância"),
				}))
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> VanillinCompat.set(entry, value),
						data -> VanillinCompat.get(entry))
				.build();
	}

	/**
	 * The default is spelled out on the row rather than inferred from the current value, because
	 * the two are different things: an entry still on {@code Default} has never been touched, and
	 * an experimental visual sitting on {@code Default} is off in a production environment.
	 */
	private static Text tooltip(VanillinCompat.Entry entry, @Nullable Info info) {
		String description = info != null ? info.description() : FALLBACK_DESCRIPTION;

		String state = entry.enabledByDefault()
				? "\n\nEstado «Padrão»: ligado — o Vanillin traz este visual como estável. Aplicar "
						+ "reconstrói os visuais que já estão no ecrã."
				: "\n\nEstado «Padrão»: desligado — o Vanillin traz este visual como experimental fora "
						+ "de desenvolvimento. Escolhe «Forçar instância» para o ligar. Aplicar reconstrói "
						+ "os visuais que já estão no ecrã.";

		return Text.literal(description + state + "\n\n" + entry.key());
	}
}
