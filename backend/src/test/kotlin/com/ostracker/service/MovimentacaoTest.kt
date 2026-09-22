package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.api.DespacharRequest
import com.ostracker.api.NovaOrdemRequest
import com.ostracker.api.NovoFluxoRequest
import com.ostracker.api.ReceberRequest
import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import com.ostracker.domain.SetorNome
import com.ostracker.domain.SetorNome.*
import com.ostracker.domain.StatusFluxo
import com.ostracker.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A tela do setor: o operador so recebe, devolve e despacha - e so para onde a matriz
 * de transicao deixa.
 */
class MovimentacaoTest : TesteIntegracao() {

    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var setores: SetorRepository
    @Autowired lateinit var espera: EsperaService
    @Autowired lateinit var ordens: com.ostracker.repository.OrdemServicoRepository
    @Autowired lateinit var agendamentos: com.ostracker.repository.AgendamentoRepository
    @Autowired lateinit var adesivadores: com.ostracker.repository.AdesivadorRepository

    private fun setor(nome: SetorNome) = setores.findByNome(nome)!!.id!!
    private val criacao get() = autor("criacao@ostracker.com")
    private val impressao get() = autor("impressao@ostracker.com")
    private val recorte get() = autor("recorte@ostracker.com")

    /** Abre uma OS pelo comercial com um fluxo entrando na Criacao; devolve o id do fluxo. */
    private fun abrirOs(numero: String = "MOV-1", inicio: SetorNome = CRIACAO): Int =
        fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = numero, cliente = "Cliente teste",
                fluxos = listOf(NovoFluxoRequest(identificador = "Adesivo", setorInicialId = setor(inicio)))
            ),
            autor("vendedor@ostracker.com")
        ).fluxos.single().id

    private fun tela(autor: com.ostracker.security.UsuarioAutenticado) = fluxos.movimentacao(autor)

    @Test
    fun `a OS aparece para receber so no setor onde ela esta`() {
        val id = abrirOs()

        assertThat(tela(criacao).paraReceber.map { it.fluxoId }).contains(id)
        assertThat(tela(recorte).paraReceber.map { it.fluxoId }).doesNotContain(id)
    }

    @Test
    fun `recebida, a OS vai para o setor com os destinos da matriz e sem devolver`() {
        val id = abrirOs()
        fluxos.receber(id, ReceberRequest(), criacao)

        val item = tela(criacao).noSetor.single { it.fluxoId == id }

        assertThat(item.destinos.map { it.setor }).containsExactlyInAnyOrder(RECORTE, IMPRESSAO, ACABAMENTO, PREPARACAO, FROTA)
        assertThat(item.devolverPara).isNull() // entrou pelo comercial: nao ha setor anterior
        assertThat(item.recebidoPor).isNotBlank()
    }

    @Test
    fun `despachar leva para o proximo setor e ali da para devolver a quem mandou`() {
        val id = abrirOs()
        fluxos.receber(id, ReceberRequest(), criacao)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(IMPRESSAO)), criacao)

        assertThat(tela(criacao).noSetor.map { it.fluxoId }).doesNotContain(id)
        fluxos.receber(id, ReceberRequest(), impressao)
        val item = tela(impressao).noSetor.single { it.fluxoId == id }

        assertThat(item.devolverPara).isEqualTo(CRIACAO)
        assertThat(item.destinos.map { it.setor }).containsExactlyInAnyOrder(RECORTE, ACABAMENTO, PREPARACAO, FROTA)
        assertThat(item.destinos.map { it.setor }).doesNotContain(CRIACAO) // voltar e pelo Devolver
    }

    @Test
    fun `devolver manda de volta e o setor que recebe de volta nao ganha um devolver em pingue-pongue`() {
        val id = abrirOs()
        fluxos.receber(id, ReceberRequest(), criacao)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(IMPRESSAO)), criacao)
        fluxos.receber(id, ReceberRequest(), impressao)

        val depois = fluxos.devolver(id, ReceberRequest(), impressao)

        assertThat(depois.fluxo.setorAtual).isEqualTo(CRIACAO)
        assertThat(depois.fluxo.status).isEqualTo(StatusFluxo.AGUARDANDO_RECEBIMENTO)
        assertThat(depois.eventos.last().tipoEvento.name).isEqualTo("RETORNO")
        fluxos.receber(id, ReceberRequest(), criacao)
        // A Impressao e destino normal da Criacao: segue pelo Despachar, nao por um "devolver".
        assertThat(tela(criacao).noSetor.single { it.fluxoId == id }.devolverPara).isNull()
    }

    @Test
    fun `nao da para despachar nem devolver antes de receber`() {
        val id = abrirOs()

        assertThatThrownBy { fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(RECORTE)), criacao) }
            .isInstanceOf(RegraDeNegocioException::class.java)
        assertThatThrownBy { fluxos.devolver(id, ReceberRequest(), criacao) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `destino fora da matriz e recusado`() {
        val id = abrirOs(inicio = RECORTE)
        fluxos.receber(id, ReceberRequest(), recorte)

        assertThatThrownBy { fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(IMPRESSAO)), recorte) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `acabamento so vai para a prateleira e frota so para o patio`() {
        val acabamento = abrirOs("MOV-A", ACABAMENTO)
        val frota = abrirOs("MOV-F", FROTA)
        fluxos.receber(acabamento, ReceberRequest(), autor("acabamento@ostracker.com"))
        fluxos.receber(frota, ReceberRequest(), autor("frota@ostracker.com"))

        assertThat(tela(autor("acabamento@ostracker.com")).noSetor.single { it.fluxoId == acabamento }.destinos.map { it.setor })
            .containsExactly(PRATELEIRA)
        assertThat(tela(autor("frota@ostracker.com")).noSetor.single { it.fluxoId == frota }.destinos.map { it.setor })
            .containsExactly(PATIO)
    }

    @Test
    fun `operador de outro setor nao mexe na OS`() {
        val id = abrirOs()

        assertThatThrownBy { fluxos.receber(id, ReceberRequest(), recorte) }
            .isInstanceOf(PermissaoNegadaException::class.java)
    }

    @Test
    fun `a consulta filtra pelo setor em que a OS esta`() {
        val naCriacao = abrirOs("MOV-C", CRIACAO)
        val noRecorte = abrirOs("MOV-R", RECORTE)

        val soRecorte = fluxos.listarFluxos(autor(), null, null, RECORTE).map { it.id }

        assertThat(soRecorte).contains(noRecorte).doesNotContain(naCriacao)
    }

    @Test
    fun `diretoria e administrador tambem abrem OS`() {
        fluxos.criarOrdem(
            NovaOrdemRequest(numeroOsErp = "MOV-D", fluxos = listOf(NovoFluxoRequest(setorInicialId = setor(CRIACAO)))),
            autor("diretoria@ostracker.com")
        )
        fluxos.criarOrdem(
            NovaOrdemRequest(numeroOsErp = "MOV-ADM", fluxos = listOf(NovoFluxoRequest(setorInicialId = setor(CRIACAO)))),
            autor("admin@ostracker.com")
        )
    }

    @Test
    fun `do Patio e da Prateleira para o Financeiro, so Comercial, Diretoria e Administrador`() {
        val frota = autor("frota@ostracker.com")
        val id = abrirOs("MOV-PATIO", FROTA)
        fluxos.receber(id, ReceberRequest(), frota)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(PATIO)), frota)
        val paraFinanceiro = DespacharRequest(setorDestinoId = setor(FINANCEIRO))

        // Quem levou ao Patio nao tira de la; nem o Financeiro puxa, nem outro setor.
        listOf(frota, autor("financeiro@ostracker.com"), impressao).forEach { quem ->
            assertThatThrownBy { fluxos.despachar(id, paraFinanceiro, quem) }
                .isInstanceOf(PermissaoNegadaException::class.java)
        }

        fluxos.despachar(id, paraFinanceiro, autor("diretoria@ostracker.com"))
    }

    @Test
    fun `a tela Patio e prateleira traz o servico da agenda junto com a OS`() {
        val frota = autor("frota@ostracker.com")
        val id = abrirOs("MOV-ESPERA", FROTA)
        val os = ordens.findByNumeroOsErpIgnoreCase("MOV-ESPERA").single()
        agendamentos.save(
            Agendamento(
                data = java.time.LocalDate.now(), adesivador = adesivadores.save(Adesivador(nome = "ESPERA", ordem = 950)),
                slotInicio = 1, descricao = "TAXI 888 SPIN COMPLETO", ordemServico = os
            )
        )
        assertThat(espera.emEspera().map { it.fluxo.id }).doesNotContain(id) // ainda na Frota

        fluxos.receber(id, ReceberRequest(), frota)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(PATIO)), frota)

        val item = espera.emEspera().single { it.fluxo.id == id }
        assertThat(item.servicos).containsExactly("TAXI 888 SPIN COMPLETO")
        assertThat(item.fluxo.criadoPor).isNotBlank() // o vendedor que abriu
    }

    @Test
    fun `chegar ao Financeiro nao encerra - ele recebe e e o unico que conclui`() {
        val acabamento = autor("acabamento@ostracker.com")
        val financeiro = autor("financeiro@ostracker.com")
        val id = abrirOs("MOV-FIN", ACABAMENTO)
        fluxos.receber(id, ReceberRequest(), acabamento)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(PRATELEIRA)), acabamento)
        // Da Prateleira para o Financeiro sai pelo comercial (RN05).
        val chegou = fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(FINANCEIRO)), autor("vendedor@ostracker.com"))

        assertThat(chegou.fluxo.encerrado).isFalse()
        assertThat(chegou.fluxo.status).isEqualTo(StatusFluxo.AGUARDANDO_RECEBIMENTO)
        assertThat(tela(financeiro).setor).isEqualTo(FINANCEIRO)
        assertThat(tela(financeiro).paraReceber.map { it.fluxoId }).contains(id)

        assertThatThrownBy { fluxos.concluir(id, ReceberRequest(), financeiro) }
            .isInstanceOf(RegraDeNegocioException::class.java) // antes de receber, nao

        fluxos.receber(id, ReceberRequest(), financeiro)
        val item = tela(financeiro).noSetor.single { it.fluxoId == id }
        assertThat(item.podeConcluir).isTrue()
        assertThat(item.destinos).isEmpty() // do Financeiro nao se despacha para lugar nenhum

        assertThatThrownBy { fluxos.concluir(id, ReceberRequest(), autor("vendedor@ostracker.com")) }
            .isInstanceOf(PermissaoNegadaException::class.java)
        assertThatThrownBy { fluxos.concluir(id, ReceberRequest(), autor("admin@ostracker.com")) }
            .isInstanceOf(PermissaoNegadaException::class.java)

        val concluido = fluxos.concluir(id, ReceberRequest(), financeiro)
        assertThat(concluido.fluxo.encerrado).isTrue()
        assertThat(concluido.fluxo.status).isEqualTo(StatusFluxo.ENCERRADA)
        assertThat(concluido.eventos.last().tipoEvento.name).isEqualTo("CONCLUSAO")
        assertThat(tela(financeiro).noSetor.map { it.fluxoId }).doesNotContain(id)
    }

    @Test
    fun `so o setor Financeiro e concluido - um operador nao conclui no proprio setor`() {
        val id = abrirOs("MOV-NC")
        fluxos.receber(id, ReceberRequest(), criacao)

        assertThatThrownBy { fluxos.concluir(id, ReceberRequest(), criacao) }
            .isInstanceOf(PermissaoNegadaException::class.java)
        assertThat(tela(criacao).noSetor.single { it.fluxoId == id }.podeConcluir).isFalse()
    }

    // --------------------------------------------- a agenda acompanha a Frota

    private fun carroDaOs(numero: String, status: com.ostracker.domain.StatusAgendamento = com.ostracker.domain.StatusAgendamento.PROGRAMADO) =
        agendamentos.save(
            Agendamento(
                data = java.time.LocalDate.now(), adesivador = adesivadores.save(Adesivador(nome = "COL $numero $status", ordem = 960)),
                slotInicio = 1, descricao = "TAXI 888 SPIN COMPLETO", status = status,
                ordemServico = ordens.findByNumeroOsErpIgnoreCase(numero).single()
            )
        ).id!!

    private fun statusDoCarro(id: Long) = agendamentos.findById(id).get().status

    @Test
    fun `a Frota recebe - o carro da agenda executa, despacha para o Patio - concluido`() {
        val frota = autor("frota@ostracker.com")
        val id = abrirOs("MOV-AG", FROTA)
        val carro = carroDaOs("MOV-AG")
        val naoVeio = carroDaOs("MOV-AG", com.ostracker.domain.StatusAgendamento.NAO_VEIO)

        fluxos.receber(id, ReceberRequest(), frota)
        assertThat(statusDoCarro(carro)).isEqualTo(com.ostracker.domain.StatusAgendamento.EXECUTANDO)

        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(PATIO)), frota)
        assertThat(statusDoCarro(carro)).isEqualTo(com.ostracker.domain.StatusAgendamento.CONCLUIDO)
        // O que foi marcado na mao continua como estava.
        assertThat(statusDoCarro(naoVeio)).isEqualTo(com.ostracker.domain.StatusAgendamento.NAO_VEIO)
    }

    @Test
    fun `receber em outro setor nao mexe na agenda, e a Frota devolvendo volta a programado`() {
        val frota = autor("frota@ostracker.com")
        val id = abrirOs("MOV-AG2", IMPRESSAO)
        val carro = carroDaOs("MOV-AG2")

        fluxos.receber(id, ReceberRequest(), impressao)
        assertThat(statusDoCarro(carro)).isEqualTo(com.ostracker.domain.StatusAgendamento.PROGRAMADO)

        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(FROTA)), impressao)
        fluxos.receber(id, ReceberRequest(), frota)
        assertThat(statusDoCarro(carro)).isEqualTo(com.ostracker.domain.StatusAgendamento.EXECUTANDO)

        fluxos.devolver(id, ReceberRequest(), frota)
        assertThat(statusDoCarro(carro)).isEqualTo(com.ostracker.domain.StatusAgendamento.PROGRAMADO)
    }
}
