package com.rastros.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** A previsao do dia para a linha do painel. */
data class ClimaResponse(
    val cidade: String,
    /** Temperatura agora, em graus. */
    val temperatura: Int,
    val minima: Int,
    val maxima: Int,
    /** Chance de chuva no dia, em %. */
    val chanceDeChuva: Int?,
    /** Codigo do tempo agora (WMO): a tela transforma em palavra e icone. */
    val codigo: Int,
    val atualizadoEm: Instant
)

/**
 * Previsao do tempo para o painel, do Open-Meteo (gratuito e sem chave). So vai a
 * localizacao da empresa, nada do sistema.
 *
 * Quem busca e o servidor, uma vez a cada 20 minutos para todo mundo; as telas pegam a
 * copia guardada. Sem internet, o painel mostra a ultima previsao que conseguiu - ou fica
 * sem ela, e o resto do painel segue normal.
 */
@Service
class ClimaService(
    private val json: ObjectMapper,
    @Value("\${clima.cidade:Sao Paulo}") private val cidade: String,
    @Value("\${clima.latitude:-23.5505}") private val latitude: Double,
    @Value("\${clima.longitude:-46.6333}") private val longitude: Double
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()

    @Volatile private var guardado: ClimaResponse? = null
    @Volatile private var ultimaTentativa: Instant = Instant.EPOCH

    fun agora(): ClimaResponse? {
        val copia = guardado
        val agora = Instant.now()
        val velha = copia == null || copia.atualizadoEm.isBefore(agora.minus(VALIDADE))
        // Sem internet, nao tenta de novo a cada tela aberta: espera um pouco entre tentativas.
        if (velha && ultimaTentativa.isBefore(agora.minus(ESPERA_ENTRE_TENTATIVAS))) {
            synchronized(this) {
                if (guardado === copia) {
                    ultimaTentativa = agora
                    buscar()?.let { guardado = it }
                }
            }
        }
        return guardado
    }

    private fun buscar(): ClimaResponse? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=America%2FSao_Paulo&forecast_days=1"
        val resposta = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (resposta.statusCode() != 200) null
        else {
            val raiz = json.readTree(resposta.body())
            val atual = raiz["current"]
            val dia = raiz["daily"]
            ClimaResponse(
                cidade = cidade,
                temperatura = Math.round(atual["temperature_2m"].asDouble()).toInt(),
                minima = Math.round(dia["temperature_2m_min"][0].asDouble()).toInt(),
                maxima = Math.round(dia["temperature_2m_max"][0].asDouble()).toInt(),
                chanceDeChuva = dia["precipitation_probability_max"]?.get(0)?.takeUnless { it.isNull }?.asInt(),
                codigo = atual["weather_code"].asInt(),
                atualizadoEm = Instant.now()
            )
        }
    } catch (e: Exception) {
        log.info("Previsao do tempo indisponivel agora: {}", e.message)
        null
    }

    private companion object {
        val VALIDADE: Duration = Duration.ofMinutes(20)
        val ESPERA_ENTRE_TENTATIVAS: Duration = Duration.ofMinutes(5)
    }
}
