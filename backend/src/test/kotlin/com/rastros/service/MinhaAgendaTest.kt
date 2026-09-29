package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.DespacharRequest
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.api.ReceberRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.PerfilNome
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.StatusFluxo
import com.rastros.domain.Usuario
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.FluxoOsRepository
import com.rastros.repository.OrdemServicoRepository
import com.rastros.repository.PerfilRepository
import com.rastros.repository.SetorRepository
import com.rastros.security.paraAutenticado
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A "Minha agenda" do adesivador: so a coluna dele, dia a dia, trabalhando por **projetos**.
 * Ele inicia e conclui; a OS anda sozinha dentro da Frota.
 */
class MinhaAgendaTest : TesteIntegracao() {

    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var fluxoRepository: FluxoOsRepository
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var ordens: OrdemServicoRepository
    @Autowired lateinit var setores: SetorRepository
    @Autowired lateinit var perfis: PerfilRepository
    @Autowired lateinit var produtividade: ProdutividadeService

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val heitor get() = autor("frota@rastros.cloud") // Heitor Frota
    private val impressao get() = autor("impressao@rastros.cloud")
    private lateinit var minhaColuna: Adesivador
    private lateinit var outraColuna: Adesivador

    @BeforeEach
    fun preparar() {
        minhaColuna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
        outraColuna = adesivadores.save(Adesivador(nome = "OUTRO", ordem = 2))
    }

    /** OS aberta direto na Frota, esperando ser recebida. */
    private fun osNaFrota(numero: String): Int = abrir(numero, SetorNome.FROTA)

    /** OS ainda na Impressao: o material nao chegou na Frota. */
    private fun osNaImpressao(numero: String): Int = abrir(numero, SetorNome.IMPRESSAO)

    private fun abrir(numero: String, setor: SetorNome): Int = fluxos.criarOrdem(
        NovaOrdemRequest(
            numeroOsErp = numero, cliente = "Cooperativa Vila Nova", servico = "Adesivagem de frota",
            fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(setor)!!.id!!))
        ),
        autor("vendedor@rastros.cloud")
    ).fluxos.single().id

    /** A Impressao termina e manda para a Frota. */
    private fun impressaoMandaParaAFrota(fluxo: Int) {
        fluxos.receber(fluxo, ReceberRequest(), impressao)
        fluxos.despachar(fluxo, DespacharRequest(setorDestinoId = setores.findByNome(SetorNome.FROTA)!!.id), impressao)
    }

    private fun projeto(coluna: Adesivador, dia: LocalDate, faixa: Int, horas: String, descricao: String, os: String? = null) =
        agendamentos.save(
            Agendamento(
                data = dia, adesivador = coluna, slotInicio = faixa, horasEstimadas = BigDecimal(horas),
                descricao = descricao, ordemServico = os?.let { ordens.findByNumeroOsErpIgnoreCase(it).single() }
            )
        ).id!!

    private fun fluxo(id: Int) = fluxoRepository.findById(id).get()
    private fun status(id: Long) = agendamentos.findById(id).get().status

    /** Mais um adesivador da Frota, com login proprio e coluna com o nome dele. */
    private fun outroAdesivadorDaFrota(): Usuario = usuarioRepository.save(
        Usuario(
            nome = "Outro", login = "outro.frota", email = "outro.frota@rastros.cloud", senhaHash = "x",
            perfil = perfis.findByNome(PerfilNome.OPERACIONAL)!!, setor = setores.findByNome(SetorNome.FROTA)
        )
    )

    @Test
    fun `mostra so os projetos da coluna dele, prontos para iniciar`() {
        osNaFrota("MA-1")
        projeto(minhaColuna, segunda, 1, "3.5", "TAXI 888 SPIN COMPLETO", "MA-1")
        projeto(outraColuna, segunda, 1, "2", "DE OUTRO")

        val dia = minhaAgenda.doDia(segunda, heitor)

        assertThat(dia.adesivador).isEqualTo("HEITOR FROTA")
        val carro = dia.carros.single()
        assertThat(carro.descricao).isEqualTo("TAXI 888 SPIN COMPLETO")
        assertThat(carro.horarioInicio).isEqualTo("07:30")
        assertThat(carro.horarioFim).isEqualTo("11:00")
        assertThat(carro.os!!.servico).isEqualTo("Adesivagem de frota")
        assertThat(carro.podeIniciar).isTrue()
        assertThat(carro.podeConcluir).isFalse()
    }

    @Test
    fun `iniciar com a OS ja na Frota recebe a OS em nome do adesivador`() {
        val fluxo = osNaFrota("MA-2")
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 123", "MA-2")

        val feito = minhaAgenda.iniciar(id, heitor)

        assertThat(feito.recebidas).isEqualTo(1)
        assertThat(status(id)).isEqualTo(StatusAgendamento.EXECUTANDO)
        assertThat(fluxo(fluxo).statusAtual).isEqualTo(StatusFluxo.EM_PROCESSAMENTO)
        assertThat(fluxo(fluxo).recebidoPor!!.nome).isEqualTo("Heitor Frota")
        val carro = minhaAgenda.doDia(segunda, heitor).carros.single()
        assertThat(carro.atual).isTrue()
        assertThat(carro.podeConcluir).isTrue()
        assertThat(carro.iniciadoPor).isEqualTo("Heitor Frota")
    }

    @Test
    fun `iniciado antes da OS chegar, ela e recebida sozinha quando a Frota a recebe`() {
        val fluxo = osNaImpressao("MA-3")
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 321", "MA-3")

        val feito = minhaAgenda.iniciar(id, heitor)
        assertThat(feito.recebidas).isEqualTo(0)
        assertThat(feito.osACaminho).isTrue()

        impressaoMandaParaAFrota(fluxo)

        assertThat(fluxo(fluxo).setorAtual.nome).isEqualTo(SetorNome.FROTA)
        assertThat(fluxo(fluxo).statusAtual).isEqualTo(StatusFluxo.EM_PROCESSAMENTO)
        assertThat(fluxo(fluxo).recebidoPor!!.nome).isEqualTo("Heitor Frota")
    }

    @Test
    fun `sem iniciar o projeto, a OS que chega na Frota fica esperando`() {
        val fluxo = osNaImpressao("MA-4")
        projeto(minhaColuna, segunda, 1, "3.5", "TAXI 654", "MA-4")

        impressaoMandaParaAFrota(fluxo)

        assertThat(fluxo(fluxo).statusAtual).isEqualTo(StatusFluxo.AGUARDANDO_RECEBIMENTO)
    }

    @Test
    fun `concluir o projeto manda a OS da Frota para o Patio`() {
        val fluxo = osNaFrota("MA-5")
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 456", "MA-5")
        minhaAgenda.iniciar(id, heitor)

        val feito = minhaAgenda.concluir(id, heitor)

        assertThat(feito.paraOPatio).isEqualTo(1)
        assertThat(status(id)).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(fluxo(fluxo).setorAtual.nome).isEqualTo(SetorNome.PATIO)
        val carro = minhaAgenda.doDia(segunda, heitor).carros.single()
        assertThat(carro.atual).isFalse()
        assertThat(carro.podeIniciar).isFalse()
        assertThat(carro.podeConcluir).isFalse()
        assertThat(carro.concluidoEm).isNotNull()
    }

    @Test
    fun `OS de varios carros so vai para o Patio quando o ultimo projeto termina`() {
        val outro = outroAdesivadorDaFrota()
        val colunaDoOutro = adesivadores.save(Adesivador(nome = "OUTRO FROTA", ordem = 3, usuario = outro))
        val fluxo = osNaFrota("MA-6")
        val meu = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 1 DA FROTA", "MA-6")
        val dele = projeto(colunaDoOutro, segunda, 1, "3.5", "TAXI 2 DA FROTA", "MA-6")

        minhaAgenda.iniciar(meu, heitor)
        val primeiro = minhaAgenda.concluir(meu, heitor)

        assertThat(primeiro.paraOPatio).isEqualTo(0)
        assertThat(primeiro.faltam).containsExactly("TAXI 2 DA FROTA")
        assertThat(fluxo(fluxo).setorAtual.nome).isEqualTo(SetorNome.FROTA)

        minhaAgenda.iniciar(dele, outro.paraAutenticado())
        val ultimo = minhaAgenda.concluir(dele, outro.paraAutenticado())

        assertThat(ultimo.paraOPatio).isEqualTo(1)
        assertThat(fluxo(fluxo).setorAtual.nome).isEqualTo(SetorNome.PATIO)
    }

    @Test
    fun `concluido antes de a OS chegar, ela passa direto para o Patio quando chega`() {
        val fluxo = osNaImpressao("MA-7")
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 777", "MA-7")
        minhaAgenda.iniciar(id, heitor)
        val feito = minhaAgenda.concluir(id, heitor)
        assertThat(feito.osACaminho).isTrue()

        impressaoMandaParaAFrota(fluxo)

        assertThat(fluxo(fluxo).setorAtual.nome).isEqualTo(SetorNome.PATIO)
    }

    @Test
    fun `concluir exige o projeto iniciado, e iniciar de novo e recusado`() {
        osNaFrota("MA-8")
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 888", "MA-8")

        assertThatThrownBy { minhaAgenda.concluir(id, heitor) }.isInstanceOf(RegraDeNegocioException::class.java)
        minhaAgenda.iniciar(id, heitor)
        assertThatThrownBy { minhaAgenda.iniciar(id, heitor) }.isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `projeto de outra coluna nao se inicia`() {
        val id = projeto(outraColuna, segunda, 1, "2", "DE OUTRO")

        assertThatThrownBy { minhaAgenda.iniciar(id, heitor) }.isInstanceOf(PermissaoNegadaException::class.java)
    }

    @Test
    fun `devolver continua possivel com a OS recebida e o projeto em andamento`() {
        val fluxo = osNaImpressao("MA-9")
        impressaoMandaParaAFrota(fluxo)
        val id = projeto(minhaColuna, segunda, 1, "3.5", "TAXI 999", "MA-9")
        minhaAgenda.iniciar(id, heitor)

        val os = minhaAgenda.doDia(segunda, heitor).carros.single().os!!
        assertThat(os.devolverPara).isEqualTo(SetorNome.IMPRESSAO)

        fluxos.devolver(fluxo, ReceberRequest(), heitor)
        assertThat(status(id)).isEqualTo(StatusAgendamento.PROGRAMADO) // o servico parou
    }

    @Test
    fun `concluido pelo adesivador, o projeto continua na lista do dia dele, como concluido`() {
        val id = projeto(minhaColuna, segunda, 1, "2", "CONCLUIDO NO DIA")
        minhaAgenda.iniciar(id, heitor)
        minhaAgenda.concluir(id, heitor)

        val naAgenda = minhaAgenda.doDia(segunda, heitor).carros.single { it.agendamentoId == id }
        val noPainel = minhaAgenda.agendasDoDia(segunda).single { it.adesivador == "HEITOR FROTA" }.carros
            .single { it.agendamentoId == id }

        assertThat(naAgenda.status).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(naAgenda.iniciadoEm).isNotNull()
        assertThat(naAgenda.concluidoEm).isNotNull()
        assertThat(noPainel.status).isEqualTo(StatusAgendamento.CONCLUIDO)
    }

    @Test
    fun `o relatorio traz o horario real de inicio e de conclusao`() {
        val id = projeto(minhaColuna, segunda, 1, "2", "TAXI DO RELATORIO")
        minhaAgenda.iniciar(id, heitor)
        minhaAgenda.concluir(id, heitor)

        val servico = produtividade.relatorio(segunda, segunda.plusDays(4)).adesivadores
            .single { it.adesivador == "HEITOR FROTA" }.servicos.single()

        assertThat(servico.iniciadoEm).isNotNull()
        assertThat(servico.concluidoEm).isNotNull()
        assertThat(servico.concluidoEm).isAfterOrEqualTo(servico.iniciadoEm)
    }

    @Test
    fun `servico longo aparece em cada dia que ocupa, com o horario daquele dia`() {
        projeto(minhaColuna, segunda, 6, "13.5", "CAMINHAO BAU") // seg 13:30-18:00 (4.5h) + ter inteira (9h)

        val terca = minhaAgenda.doDia(segunda.plusDays(1), heitor).carros.single()

        assertThat(terca.horarioInicio).isEqualTo("07:30")
        assertThat(terca.horarioFim).isEqualTo("18:00")
        assertThat(terca.comecaEm).isEqualTo(segunda)
        assertThat(minhaAgenda.doDia(segunda.plusDays(2), heitor).carros).isEmpty()
    }

    @Test
    fun `quem nao adesiva nao tem agenda`() {
        assertThatThrownBy { minhaAgenda.doDia(segunda, impressao) }
            .isInstanceOf(NaoEncontradoException::class.java)
        assertThat(minhaAgenda.colunaDe(usuarioRepository.findByEmailIgnoreCase("impressao@rastros.cloud")!!)).isNull()
    }

    @Test
    fun `o painel mostra a agenda de cada adesivador, com o projeto atual marcado`() {
        val id = projeto(minhaColuna, segunda, 1, "2", "EM ANDAMENTO")
        projeto(minhaColuna, segunda, 3, "2", "DEPOIS")
        minhaAgenda.iniciar(id, heitor)

        val agendas = minhaAgenda.agendasDoDia(segunda)

        val minha = agendas.single { it.adesivador == "HEITOR FROTA" }
        assertThat(minha.carros.map { it.descricao }).containsExactly("EM ANDAMENTO", "DEPOIS")
        assertThat(minha.carros.map { it.atual }).containsExactly(true, false)
        // No painel ninguem inicia nem conclui: e so o espelho.
        assertThat(minha.carros.none { it.podeIniciar || it.podeConcluir }).isTrue()
        assertThat(agendas.single { it.adesivador == "OUTRO" }.carros).isEmpty()
    }

    @Test
    fun `o painel lista as OS do Acabamento com a data de chegada`() {
        val fluxo = abrir("AC-1", SetorNome.ACABAMENTO)

        val lista = fluxos.noSetor(SetorNome.ACABAMENTO).filter { it.numeroOsErp == "AC-1" }

        val os = lista.single()
        assertThat(os.fluxoId).isEqualTo(fluxo)
        assertThat(os.cliente).isEqualTo("Cooperativa Vila Nova")
        assertThat(os.servico).isEqualTo("Adesivagem de frota")
        assertThat(os.status).isEqualTo(StatusFluxo.AGUARDANDO_RECEBIMENTO)
        assertThat(os.chegouEm).isNotNull()
    }
}
