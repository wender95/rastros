package com.rastros.service

import com.rastros.domain.Adesivador
import com.rastros.domain.FaixasDoDia
import com.rastros.repository.AgendamentoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

/**
 * Reacomoda a agenda de um adesivador depois de uma edicao, empurrando para baixo quem
 * a edicao atingiu.
 *
 * Regras:
 * - um servico nunca sobe: ele fica onde esta, a menos que alguem o empurre;
 * - so e empurrado quem encosta num servico `editado` ou num que ja foi empurrado
 *   agora - a "onda" da edicao. Uma sobreposicao antiga entre dois servicos que
 *   ninguem tocou (a base importada tem varias) fica como esta: corrigi-la aqui
 *   arrastaria a agenda inteira por semanas a cada salvamento;
 * - na mesma faixa, o editado fica e o outro desce: marcar ferias as 07:30 de um dia
 *   ocupado empurra os servicos daquele dia, e nao o bloqueio;
 * - o servico que passa do fim do dia continua no proximo dia util;
 * - o almoco e pulado na hora de escolher onde um servico comeca.
 */
@Component
class ReempilhamentoAgenda(private val agendamentoRepository: AgendamentoRepository) {

    fun reempilhar(apartirDe: LocalDate, adesivador: Adesivador, editados: Set<Long>, diasUteis: Int = 20) {
        // Os dias anteriores entram so como obstaculo: um servico longo que comecou antes
        // ainda ocupa o comeco da janela.
        val anteriores = DiasUteisDaAgenda.antesDe(apartirDe, DIAS_ANTERIORES_OBSTACULO)
        val dias = anteriores + DiasUteisDaAgenda.aPartirDe(apartirDe, diasUteis)
        val posicaoDoDia = dias.withIndex().associate { (indice, dia) -> dia to indice }
        val faixasDoDia = FaixasDoDia.QUANTIDADE
        val limiteDaJanela = dias.size * faixasDoDia

        val itens = agendamentoRepository
            .doPeriodoDoAdesivador(dias.first(), dias.last(), adesivador.id!!)
            .filter { posicaoDoDia.containsKey(it.data) }
            .sortedWith(compareBy({ it.data }, { it.slotInicio }, { if (it.id in editados) 0 else 1 }, { it.id }))

        // Ultima posicao tomada por qualquer servico ja acomodado.
        var ocupadoAte = -1
        // Ultima posicao tomada por quem foi editado ou empurrado nesta passada.
        var ondaAte = -1

        for (item in itens) {
            val atual = posicaoDoDia[item.data]!! * faixasDoDia + (item.slotInicio - 1)
            val naOnda = item.id in editados || atual <= ondaAte
            if (!naOnda) {
                ocupadoAte = maxOf(ocupadoAte, FaixasDoDia.posicoesAPartirDe(dias.first(), atual, item.horas).last())
                continue
            }

            var posicao = maxOf(atual, ocupadoAte + 1)
            // O almoco e a sexta depois das 17:00 nunca recebem o inicio de um servico.
            posicao = FaixasDoDia.livreAPartirDe(dias.first(), posicao)

            if (posicao >= limiteDaJanela) {
                throw RegraDeNegocioException(
                    "Nao ha espaco na agenda de ${adesivador.nome} nos proximos $diasUteis dias uteis " +
                        "para encaixar \"${item.descricao}\"."
                )
            }

            val novaData = dias[posicao / faixasDoDia]
            val novaFaixa = (posicao % faixasDoDia) + 1
            if (item.data != novaData || item.slotInicio != novaFaixa) {
                item.data = novaData
                item.slotInicio = novaFaixa
                item.atualizadoEm = Instant.now()
                agendamentoRepository.save(item)
            }
            val fim = FaixasDoDia.posicoesAPartirDe(dias.first(), posicao, item.horas).last()
            ondaAte = maxOf(ondaAte, fim)
            ocupadoAte = maxOf(ocupadoAte, fim)
        }
    }

    private companion object {
        /** Quantos dias uteis antes da edicao entram como obstaculo (servico longo em curso). */
        const val DIAS_ANTERIORES_OBSTACULO = 10
    }
}
