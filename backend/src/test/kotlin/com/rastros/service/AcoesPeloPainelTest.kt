package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Horario de inicio e conclusao so conta quando o adesivador registra (ou alguem com a
 * permissao, pelo painel). Mudar o status pela agenda nao grava horario.
 */
class AcoesPeloPainelTest : TesteIntegracao() {

    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private val hoje = LocalDate.now()
    private val heitor get() = autor("frota@rastros.cloud") // o adesivador dono da coluna
    private val admin get() = autor("admin@rastros.cloud") // tem todas as secoes, inclusive agir pelo painel
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
    }

    private fun projeto(descricao: String) = agendamentos.save(
        Agendamento(data = hoje, adesivador = coluna, slotInicio = 1, horasEstimadas = BigDecimal.ONE, descricao = descricao)
    ).id!!

    private fun linhaDoRelatorio(id: Long) = produtividade.relatorio(hoje, hoje).adesivadores
        .single { it.adesivadorId == coluna.id }.servicos.single { it.agendamentoId == id }

    @Test
    fun `mudar o status pela agenda nao grava horario de inicio nem de conclusao`() {
        val id = projeto("TAXI DO ESCRITORIO")
        agenda.alterarStatus(id, StatusAgendamento.EXECUTANDO, autor())
        agenda.alterarStatus(id, StatusAgendamento.CONCLUIDO, autor())

        val salvo = agendamentos.findById(id).get()
        assertThat(salvo.status).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(salvo.iniciadoEm).isNull()
        assertThat(salvo.concluidoEm).isNull()
        assertThat(linhaDoRelatorio(id).iniciadoEm).isNull()
    }

    @Test
    fun `pelo painel, a OS anda em nome do adesivador e o relatorio mostra quem clicou`() {
        val id = projeto("VAN PELO PAINEL")
        minhaAgenda.agirPeloPainel(id, "iniciar", admin)
        minhaAgenda.agirPeloPainel(id, "pausar", admin)
        minhaAgenda.agirPeloPainel(id, "retomar", admin)
        minhaAgenda.agirPeloPainel(id, "concluir", admin)

        val salvo = agendamentos.findById(id).get()
        assertThat(salvo.iniciadoPor?.login).isEqualTo("frota") // o dono da coluna
        assertThat(salvo.inicioRegistradoPor?.login).isEqualTo("admin")
        assertThat(salvo.conclusaoRegistradaPor?.login).isEqualTo("admin")

        val linha = linhaDoRelatorio(id)
        val nomeDoAdmin = usuarioRepository.findByLoginIgnoreCase("admin")!!.nome
        assertThat(linha.iniciadoPor).isEqualTo(nomeDoAdmin)
        assertThat(linha.concluidoPor).isEqualTo(nomeDoAdmin)
        assertThat(linha.pausas.single().pausadoPor).isEqualTo(nomeDoAdmin)
        assertThat(linha.pausas.single().retomadoPor).isEqualTo(nomeDoAdmin)
    }

    @Test
    fun `pela Minha agenda, o relatorio mostra o proprio adesivador`() {
        val id = projeto("CAMINHAO DO HEITOR")
        minhaAgenda.iniciar(id, heitor)
        minhaAgenda.concluir(id, heitor)

        val linha = linhaDoRelatorio(id)
        assertThat(linha.iniciadoPor).isEqualTo("Heitor Frota")
        assertThat(linha.concluidoPor).isEqualTo("Heitor Frota")
    }

    @Test
    fun `agir pelo painel precisa da permissao, que a Diretoria nao traz pronta`() {
        val id = projeto("ONIBUS")
        assertThatThrownBy { minhaAgenda.agirPeloPainel(id, "iniciar", autor("diretoria@rastros.cloud")) }
            .isInstanceOf(PermissaoNegadaException::class.java)
        assertThatThrownBy { minhaAgenda.agirPeloPainel(id, "concluir", admin) }
            .isInstanceOf(RegraDeNegocioException::class.java) // nao iniciado ainda
    }
}
