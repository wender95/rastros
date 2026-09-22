package com.ostracker.service

import com.ostracker.api.ResultadoImportacaoResponse
import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import com.ostracker.domain.FaixasDoDia
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.TipoAgendamento
import com.ostracker.domain.TipoColunaAgenda
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Le uma aba mensal do cronograma (CSV) e grava os agendamentos.
 *
 * Formato de origem, por bloco semanal:
 *
 * ```
 * ,DIA 05/01 À 09/01,...
 * ,01 ANDRE,,02 BRUNO,,...,ENCAIXE,NOTURNO      <- nomes; a coluna seguinte guarda o score
 * SEG,REMOÇÃO KICKS (P) OK,21,...                 <- 1a linha do dia
 * ,REMOÇÃO KICKS (P) OK,,...                      <- linhas seguintes
 * ...
 * TER,...
 * ```
 *
 * Desde que a agenda passou a viver no OS Tracker a planilha e historico. Por isso a
 * importacao **so grava em periodo vazio**: se o sistema ja tem agenda em algum dia que a
 * aba cobre, ela e recusada inteira. Importar por cima duplicaria carros ou desfaria o
 * que foi remanejado aqui.
 */
@Service
class ImportadorAgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** O dia da planilha tinha 5 linhas; quando alguem usava uma 6a, o dia se dividia em 6. */
    private val LINHAS_DO_DIA_NA_PLANILHA = 5

    private val regexData = Regex("""(\d{1,2})/(\d{1,2})""")
    private val regexDiaSemana = Regex("""^(SEG|TER|QUA|QUI|SEX)""", RegexOption.IGNORE_CASE)
    private val regexStatusFinal = Regex("""\s*\b(OK|N|P|E|X)\s*$""", RegexOption.IGNORE_CASE)
    private val regexVendedor = Regex("""\(\s*([A-Za-z])\s*\)""")
    private val regexVendedorSemAbre = Regex("""\s([A-Za-z])\)\s*$""")
    private val regexOrdemColuna = Regex("""^(\d{1,2})\s+(.+)$""")

    /** Um dia de uma coluna, com o que estava escrito em cada linha. */
    private class DiaLido(val dia: LocalDate, val adesivador: Adesivador, val porLinha: Map<Int, CelulaAgenda>)

    /**
     * @param aPartirDe dias antes dele sao ignorados - para colocar em uso so a agenda de
     * hoje em diante, sem trazer o historico da planilha.
     */
    @Transactional
    fun importar(aba: String, ano: Int, csv: String, aPartirDe: LocalDate? = null): ResultadoImportacaoResponse {
        val avisos = mutableListOf<String>()
        val colunasNovas = mutableListOf<String>()
        val (intervalos, dias) = lerAba(lerCsv(csv), ano, avisos, colunasNovas, aPartirDe)

        if (intervalos.isNotEmpty()) {
            val inicio = intervalos.minOf { it.first }
            val fim = intervalos.maxOf { it.second }
            val existentes = agendamentoRepository.countByDataBetween(inicio, fim)
            if (existentes > 0) {
                throw RegraDeNegocioException(
                    "A aba $aba cobre de ${inicio.format(DATA_CURTA)} a ${fim.format(DATA_CURTA)}, e o " +
                        "sistema ja tem $existentes agendamento(s) nesse periodo. A agenda agora e " +
                        "mantida no OS Tracker: importar por cima duplicaria ou desfaria o que foi " +
                        "remanejado aqui."
                )
            }
        }

        val criados = dias.sumOf { gravarDia(it, avisos) }
        val celulasLidas = dias.sumOf { it.porLinha.size }

        log.info("Importacao da aba {}: {} semanas, {} agendamentos criados.", aba, intervalos.size, criados)
        return ResultadoImportacaoResponse(
            aba = aba,
            semanas = intervalos.size,
            celulasLidas = celulasLidas,
            criados = criados,
            colunasNovas = colunasNovas.distinct(),
            avisos = avisos
        )
    }

    /** Percorre os blocos semanais da aba e devolve o que ha escrito em cada dia de cada coluna. */
    private fun lerAba(
        linhas: List<List<String>>,
        ano: Int,
        avisos: MutableList<String>,
        colunasNovas: MutableList<String>,
        aPartirDe: LocalDate? = null
    ): Pair<List<Pair<LocalDate, LocalDate>>, List<DiaLido>> {
        val intervalos = mutableListOf<Pair<LocalDate, LocalDate>>()
        val dias = mutableListOf<DiaLido>()

        var i = 0
        while (i < linhas.size) {
            val lido = extrairIntervalo(linhas[i], ano)
            if (lido == null) {
                i++
                continue
            }
            // Semana inteira antes do corte: fica de fora, e os dias dela nao contam.
            val intervalo = if (aPartirDe == null) lido else maxOf(lido.first, aPartirDe) to lido.second
            val foraDoCorte = intervalo.first.isAfter(intervalo.second)
            val cabecalho = linhas.getOrNull(i + 1)
            if (cabecalho == null) {
                avisos += "Bloco iniciado na linha ${i + 1} nao tem linha de nomes; ignorado."
                i++
                continue
            }

            val colunas = mapearColunas(cabecalho, colunasNovas)
            if (colunas.isEmpty()) {
                avisos += "Bloco de ${intervalo.first} nao tem colunas reconheciveis; ignorado."
                i += 2
                continue
            }
            if (!foraDoCorte) intervalos += intervalo

            val segunda = intervalo.first.with(DayOfWeek.MONDAY)
            var linha = i + 2
            var diaAtual: LocalDate? = null
            var linhaDoDia = 0
            // Uma celula por (coluna, dia, linha) antes de juntar linhas repetidas em um servico so.
            val celulas = mutableMapOf<Pair<Int, LocalDate>, MutableMap<Int, CelulaAgenda>>()

            while (linha < linhas.size) {
                val atual = linhas[linha]
                val primeira = atual.getOrElse(0) { "" }.trim()

                if (extrairIntervalo(atual, ano) != null) break
                if (primeira.uppercase().startsWith("TOTAL") || primeira.lowercase().startsWith("legenda")) break

                val marcador = regexDiaSemana.find(primeira)
                if (marcador != null) {
                    diaAtual = segunda.plusDays(deslocamentoDoDia(marcador.groupValues[1]))
                    linhaDoDia = 0
                } else if (diaAtual == null) {
                    linha++
                    continue
                }

                linhaDoDia++
                val dia = diaAtual!!
                if (!foraDoCorte && !dia.isBefore(intervalo.first) && !dia.isAfter(intervalo.second)) {
                    colunas.forEach { coluna ->
                        val celula = interpretarCelula(atual.getOrElse(coluna.indice) { "" })
                        if (celula != null) {
                            celula.score = coluna.indiceScore
                                ?.let { atual.getOrElse(it) { "" } }
                                ?.let { lerScore(it) }
                            celulas.getOrPut(coluna.indice to dia) { mutableMapOf() }[linhaDoDia] = celula
                        }
                    }
                }
                linha++
            }

            colunas.forEach { coluna ->
                celulas.filterKeys { it.first == coluna.indice }.forEach { (chave, porLinha) ->
                    dias += DiaLido(chave.second, coluna.adesivador, porLinha)
                }
            }
            i = linha
        }
        return intervalos to dias
    }

    // ------------------------------------------------------------------ gravacao

    private fun gravarDia(lido: DiaLido, avisos: MutableList<String>): Int {
        val servicos = servicosDoDia(lido, avisos)
        servicos.forEach { servico ->
            agendamentoRepository.save(
                Agendamento(
                    data = lido.dia,
                    adesivador = lido.adesivador,
                    slotInicio = servico.faixas.first(),
                    tipo = TipoAgendamento.SERVICO,
                    duracaoSlots = servico.linhas,
                    horasEstimadas = servico.faixas.fold(BigDecimal.ZERO) { soma, f -> soma + FaixasDoDia.de(f).horas },
                    descricao = servico.celula.descricao,
                    vendedorCodigo = servico.celula.vendedor,
                    status = servico.celula.status,
                    score = servico.score
                )
            )
        }
        return servicos.size
    }

    /** Um servico do dia ja montado: as linhas repetidas juntas, com as faixas que ocupa. */
    private class ServicoLido(val celula: CelulaAgenda, val linhas: Int, val faixas: List<Int>, val score: BigDecimal?)

    /**
     * Linhas seguidas com o mesmo servico viram um agendamento so, e cada linha da planilha
     * vira as faixas de horario que caem no pedaco do dia que ela representava: com 5
     * linhas cada uma vale 1,8h; com 6, 1,5h.
     *
     * Assim o dia importado sempre cabe nas 9 horas uteis - nada vaza para o dia seguinte
     * nem fica em cima de outro servico - e uma linha vazia na planilha vira horario livre.
     */
    private fun servicosDoDia(lido: DiaLido, avisos: MutableList<String>): List<ServicoLido> {
        val porLinha = lido.porLinha
        val linhasDoDia = maxOf(LINHAS_DO_DIA_NA_PLANILHA, porLinha.keys.max())
        // O dia da planilha cabe nas faixas que o dia tem: 9h, e 8h na sexta (fecha as 17:00).
        val meioDasFaixas = meioDasFaixas(lido.dia)
        val horasPorLinha = FaixasDoDia.horasUteisNoDia(lido.dia).toDouble() / linhasDoDia

        fun faixasDaLinha(linha: Int): List<Int> {
            val de = (linha - 1) * horasPorLinha
            val ate = linha * horasPorLinha
            val faixas = meioDasFaixas.filter { it.second >= de && it.second < ate }.map { it.first }
            if (faixas.isNotEmpty()) return faixas
            // Mais de 8 linhas num dia: nao ha faixa sobrando, divide a mais proxima.
            avisos += "${lido.adesivador.nome} em ${lido.dia.format(DATA_CURTA)}: linha $linha sem faixa " +
                "livre; dividindo horario com o servico anterior."
            val centro = (de + ate) / 2
            return listOf(meioDasFaixas.minBy { kotlin.math.abs(it.second - centro) }.first)
        }

        val servicos = mutableListOf<ServicoLido>()
        val linhas = porLinha.keys.sorted()
        var indice = 0
        while (indice < linhas.size) {
            val primeira = linhas[indice]
            val celula = porLinha[primeira]!!
            var ultima = primeira
            // A planilha soma o score de todas as linhas no TOTAL SCORE; juntar as linhas
            // de um servico tem de somar tambem, senao o historico perde pontos.
            var score = celula.score

            var proxima = indice + 1
            while (proxima < linhas.size &&
                linhas[proxima] == ultima + 1 &&
                porLinha[linhas[proxima]]!!.chave == celula.chave
            ) {
                porLinha[linhas[proxima]]!!.score?.let { score = (score ?: BigDecimal.ZERO) + it }
                ultima = linhas[proxima]
                proxima++
            }
            indice = proxima

            val faixas = (primeira..ultima).flatMap(::faixasDaLinha).distinct().sorted()
            servicos += ServicoLido(celula, ultima - primeira + 1, faixas, score)
        }
        return servicos
    }

    // ------------------------------------------------------------------- reparo

    /**
     * Repara uma base importada pela versao antiga do importador, que pulava a semana de
     * um dia so no fim de cada aba ("DIA 31/08") e perdia score ao juntar linhas.
     *
     * Grava apenas nos dias em que o sistema nao tem **nenhum** agendamento, em nenhuma
     * coluna - essa e a marca do dia que nunca foi importado. Um dia em que alguem tirou os
     * carros de uma coluna nao se confunde com isso. Nos dias que ja existem nada e
     * alterado: as diferencas de score em relacao a planilha so vao para os avisos.
     */
    @Transactional
    fun completarDiasAusentes(aba: String, ano: Int, csv: String): ResultadoImportacaoResponse {
        val avisos = mutableListOf<String>()
        val colunasNovas = mutableListOf<String>()
        val (intervalos, dias) = lerAba(lerCsv(csv), ano, avisos, colunasNovas)

        val comAgenda = dias.map { it.dia }.distinct()
            .filter { agendamentoRepository.countByDataBetween(it, it) > 0 }
            .toSet()
        val ausentes = dias.filter { it.dia !in comAgenda }

        val criados = ausentes.sumOf { gravarDia(it, avisos) }
        dias.filter { it.dia in comAgenda }.forEach { conferirScore(it, avisos) }

        val completados = ausentes.map { it.dia }.distinct().sorted()
        log.info("Reparo da aba {}: {} dia(s) completado(s), {} agendamento(s) criado(s).", aba, completados.size, criados)
        return ResultadoImportacaoResponse(
            aba = aba,
            semanas = intervalos.size,
            celulasLidas = dias.sumOf { it.porLinha.size },
            criados = criados,
            colunasNovas = colunasNovas.distinct(),
            avisos = avisos,
            diasCompletados = completados
        )
    }

    /** Compara o score de cada servico da planilha com o que o sistema tem no mesmo dia. */
    private fun conferirScore(lido: DiaLido, avisos: MutableList<String>) {
        val noSistema = agendamentoRepository
            .findByDataAndAdesivadorIdOrderBySlotInicioAsc(lido.dia, lido.adesivador.id!!)
            .groupBy { chaveDe(it.descricao) }

        servicosDoDia(lido, mutableListOf())
            .groupBy { it.celula.chave }
            .forEach { (chave, daPlanilha) ->
                val esperado = daPlanilha.mapNotNull { it.score }.fold(BigDecimal.ZERO, BigDecimal::add)
                val encontrados = noSistema[chave]
                val atual = encontrados?.mapNotNull { it.score }?.fold(BigDecimal.ZERO, BigDecimal::add)
                val descricao = daPlanilha.first().celula.descricao
                val onde = "${lido.adesivador.nome} ${lido.dia.format(DATA_CURTA)} \"$descricao\""
                when {
                    encontrados == null && esperado.signum() != 0 ->
                        avisos += "$onde: nao esta no sistema (score $esperado na planilha)"
                    atual != null && atual.compareTo(esperado) != 0 ->
                        avisos += "$onde: score ${atual.stripTrailingZeros().toPlainString()} no sistema, " +
                            "${esperado.stripTrailingZeros().toPlainString()} na planilha"
                }
            }
    }

    private fun chaveDe(descricao: String) = descricao.uppercase().replace(Regex("""\s+"""), " ")

    // ------------------------------------------------------------- interpretacao

    private class CelulaAgenda(
        val descricao: String,
        val vendedor: String?,
        val status: StatusAgendamento,
        var score: BigDecimal? = null
    ) {
        /** Usada para reconhecer a mesma OS repetida em faixas seguidas. */
        val chave: String = descricao.uppercase().replace(Regex("""\s+"""), " ")
    }

    /**
     * `FASTBACK PPF FULL (K) OK` -> descricao, vendedor K, status CONCLUIDO.
     * Celulas que sobram apenas com o status (`n`, `N`) sao dias sem servico e viram nulo.
     */
    private fun interpretarCelula(bruto: String): CelulaAgenda? {
        var texto = bruto.trim()
        if (texto.isEmpty()) return null

        var status = StatusAgendamento.PROGRAMADO
        // O sufixo de status fica fora dos parenteses; "(P)" sozinho e o vendedor P.
        val marca = regexStatusFinal.find(texto)
        if (marca != null && !texto.endsWith(")")) {
            status = when (marca.groupValues[1].uppercase()) {
                "OK" -> StatusAgendamento.CONCLUIDO
                "N" -> StatusAgendamento.NAO_VEIO
                "P" -> StatusAgendamento.EM_PATIO
                "E" -> StatusAgendamento.EXECUTANDO
                "X" -> StatusAgendamento.EXTERNO
                else -> StatusAgendamento.PROGRAMADO
            }
            texto = texto.removeRange(marca.range).trim()
        }

        var vendedor: String? = null
        val achados = regexVendedor.findAll(texto).toList()
        if (achados.isNotEmpty()) {
            val ultimo = achados.last()
            vendedor = ultimo.groupValues[1].uppercase()
            texto = texto.removeRange(ultimo.range).trim()
        } else {
            // Ocorre na planilha sem o parentese de abertura: "PARANAGUA AMBIENTAL R)".
            val solto = regexVendedorSemAbre.find(texto)
            if (solto != null) {
                vendedor = solto.groupValues[1].uppercase()
                texto = texto.removeRange(solto.range).trim()
            }
        }

        val descricao = texto.replace(Regex("""\s+"""), " ").trim().trim('-', '/', ',').trim()
        if (descricao.isEmpty()) return null

        return CelulaAgenda(descricao.take(200), vendedor, status)
    }

    private fun lerScore(bruto: String): BigDecimal? {
        val limpo = bruto.trim().replace(",", ".")
        if (limpo.isEmpty()) return null
        return limpo.toBigDecimalOrNull()
    }

    /**
     * "DIA 01/09 À 04/09" ou so "DIA 31/08" - a ultima semana de uma aba as vezes tem um
     * dia so. A celula precisa comecar com DIA, para uma descricao que cite uma data nao
     * ser tomada por inicio de semana.
     */
    private fun extrairIntervalo(linha: List<String>, ano: Int): Pair<LocalDate, LocalDate>? {
        val celula = linha.firstOrNull {
            it.trim().uppercase().startsWith("DIA ") && regexData.containsMatchIn(it)
        } ?: return null
        val datas = regexData.findAll(celula).take(2).toList()
        return runCatching {
            val inicio = LocalDate.of(ano, datas[0].groupValues[2].toInt(), datas[0].groupValues[1].toInt())
            val fim = datas.getOrNull(1)?.let {
                val mesFim = it.groupValues[2].toInt()
                val anoFim = if (mesFim < inicio.monthValue) ano + 1 else ano
                LocalDate.of(anoFim, mesFim, it.groupValues[1].toInt())
            } ?: inicio
            inicio to fim
        }.getOrNull()
    }

    private fun deslocamentoDoDia(marcador: String) = when (marcador.uppercase()) {
        "SEG" -> 0L
        "TER" -> 1L
        "QUA" -> 2L
        "QUI" -> 3L
        else -> 4L
    }

    private class ColunaImportada(
        val indice: Int,
        val indiceScore: Int?,
        val adesivador: Adesivador
    )

    /** Le a linha de nomes e garante o cadastro de cada coluna da grade. */
    private fun mapearColunas(cabecalho: List<String>, novas: MutableList<String>): List<ColunaImportada> {
        val colunas = mutableListOf<ColunaImportada>()

        cabecalho.forEachIndexed { indice, celulaBruta ->
            val celula = celulaBruta.trim()
            if (indice == 0 || celula.isEmpty()) return@forEachIndexed

            val comOrdem = regexOrdemColuna.find(celula)
            val nome = (comOrdem?.groupValues?.get(2) ?: celula).trim().uppercase()
            val tipo = when (nome) {
                "ENCAIXE" -> TipoColunaAgenda.ENCAIXE
                "NOTURNO" -> TipoColunaAgenda.NOTURNO
                else -> TipoColunaAgenda.ADESIVADOR
            }
            val ordem = comOrdem?.groupValues?.get(1)?.toIntOrNull()
                ?: if (tipo == TipoColunaAgenda.ENCAIXE) 90 else 99

            // Quem foi tirado da agenda e aparece na planilha volta: senao os carros dele
            // entrariam numa coluna que ninguem ve.
            val adesivador = adesivadorRepository.findByNomeIgnoreCase(nome)
                ?.also { if (!it.ativo) { it.ativo = true; adesivadorRepository.save(it) } }
                ?: adesivadorRepository.save(Adesivador(nome = nome, tipo = tipo, ordem = ordem))
                    .also { novas += nome }

            // A coluna seguinte guarda o score quando nao tem nome proprio.
            val indiceScore = (indice + 1)
                .takeIf { it < cabecalho.size && cabecalho[it].isBlank() }

            colunas += ColunaImportada(indice, indiceScore, adesivador)
        }
        return colunas
    }

    // -------------------------------------------------------------- leitura csv

    /** Leitor CSV minimo (RFC 4180): campos entre aspas podem conter virgula, como "3,5". */
    private fun lerCsv(csv: String): List<List<String>> {
        val linhas = mutableListOf<List<String>>()
        var campos = mutableListOf<String>()
        val atual = StringBuilder()
        var dentroDeAspas = false
        var indice = 0

        while (indice < csv.length) {
            val caractere = csv[indice]
            when {
                dentroDeAspas && caractere == '"' && csv.getOrNull(indice + 1) == '"' -> {
                    atual.append('"')
                    indice++
                }
                caractere == '"' -> dentroDeAspas = !dentroDeAspas
                caractere == ',' && !dentroDeAspas -> {
                    campos.add(atual.toString())
                    atual.clear()
                }
                (caractere == '\n' || caractere == '\r') && !dentroDeAspas -> {
                    if (caractere == '\r' && csv.getOrNull(indice + 1) == '\n') indice++
                    campos.add(atual.toString())
                    atual.clear()
                    linhas.add(campos)
                    campos = mutableListOf()
                }
                else -> atual.append(caractere)
            }
            indice++
        }
        if (atual.isNotEmpty() || campos.isNotEmpty()) {
            campos.add(atual.toString())
            linhas.add(campos)
        }
        return linhas
    }

    private companion object {
        val DATA_CURTA: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM")

        /** Cada faixa util do dia e o meio dela, em horas de trabalho desde as 07:30 (sem almoco). */
        fun meioDasFaixas(dia: java.time.LocalDate): List<Pair<Int, Double>> {
            var acumulado = 0.0
            return FaixasDoDia.UTEIS.filterNot { FaixasDoDia.fechadaNoDia(dia, it.indice) }.map { faixa ->
                val horas = faixa.horas.toDouble()
                (faixa.indice to acumulado + horas / 2).also { acumulado += horas }
            }
        }
    }
}
