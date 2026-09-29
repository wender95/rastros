import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  BLOCOS_POR_DIA,
  horasDeIntervalo,
  linhaDoInicio,
  montarLayoutSimples,
  montarLinhasSimples,
  pedacosDeHoras,
  pedacosLivresApartirDe,
} from '../agenda/blocos'
import type { ColunaSimples, PreviaSimples } from '../agenda/blocos'
import { apiDaAgenda } from '../agenda/apiDaAgenda'
import CabecalhoDaAgenda from '../agenda/CabecalhoDaAgenda'
import type { EdicaoDaAgenda } from '../agenda/CabecalhoDaAgenda'
import CardSimples from '../agenda/CardSimples'
import { itensDoMenu } from '../agenda/menuDaAgenda'
import MenuContexto from '../agenda/MenuContexto'
import { formatarDia } from '../agenda/regua'
import { opcoesDeStatus, TECLAS_DE_STATUS } from '../agenda/statusDoCard'
import { chaveDoAlvo, useArrasteToque } from '../agenda/useArrasteToque'
import type { AlvoSolte } from '../agenda/useArrasteToque'
import { useAtalhos, useSegundoToque } from '../agenda/useAtalhos'
import type { Selecao } from '../agenda/useAtalhos'
import { useIrParaHoje, useNomesQueCabem, useToposDasLinhas } from '../agenda/useMedidasDaGrade'
import { useRedimensionarBlocos } from '../agenda/useRedimensionarBlocos'
import { useSemanasDoMes } from '../agenda/useSemanasDoMes'
import { hojeIso, mesDe } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import type { Adesivador, Agendamento, SemanaAgenda, StatusAgendamento } from '../api/tipos'
import AtribuirAdesivadores from '../agenda/AtribuirAdesivadores'
import { pode } from '../auth/acessos'
import { useAuth } from '../auth/AuthContext'
import DetalheAgendamento from '../componentes/DetalheAgendamento'
import EditarAdesivadores from '../componentes/EditarAdesivadores'
import { EditarStatus, EditarVendedores } from '../componentes/EditarLegenda'
import {
  Aviso,
  Carregando,
  formatarHoras,
  rotuloStatusAgenda,
  rotuloVendedor,
  useLegenda,
  useVendedores,
} from '../componentes/Ui'

/** Uma semana já preparada para desenhar. */
/** Uma célula no mês inteiro: a linha na régua do mês e a coluna na ordem da tela. */
interface PontoDoMes {
  linha: number
  coluna: number
}

/** A célula sob um elemento da grade (numa célula alta, a linha sob o mouse). */
function pontoSob(el: Element | null, y?: number): PontoDoMes | null {
  const td = el?.closest?.('td[data-linha-mes]') as HTMLTableCellElement | null
  if (!td) return null
  let linha = Number(td.dataset.linhaMes)
  if (td.rowSpan > 1 && y !== undefined) {
    const r = td.getBoundingClientRect()
    linha += Math.max(0, Math.min(td.rowSpan - 1, Math.floor(((y - r.top) / r.height) * td.rowSpan)))
  }
  return { linha, coluna: Number(td.dataset.colunaMes) }
}

/** Os limites do retângulo entre dois pontos. */
function limitesDa(area: { de: PontoDoMes; ate: PontoDoMes }) {
  return {
    l1: Math.min(area.de.linha, area.ate.linha),
    l2: Math.max(area.de.linha, area.ate.linha),
    c1: Math.min(area.de.coluna, area.ate.coluna),
    c2: Math.max(area.de.coluna, area.ate.coluna),
  }
}

interface SemanaPronta {
  semana: SemanaAgenda
  linhas: ReturnType<typeof montarLinhasSimples>
  layout: Map<number, ColunaSimples>
}

/**
 * A agenda do jeito da planilha antiga: sem relógio na tela, o dia dividido em **5
 * pedaços** iguais e o **mês inteiro numa página só**, uma semana abaixo da outra.
 *
 * É a mesma agenda do banco — o que muda é o desenho e os gestos: dois cliques renomeiam
 * o carro na própria célula, as bordas de cima e de baixo puxam a duração e o botão
 * direito abre o menu (estado do carro, copiar, colar, excluir).
 *
 * Regra desta tela: **nada empurra ninguém**. Quem não cabe no espaço livre é encolhido
 * até caber.
 *
 * Os pedaços que não dependem do estado da tela moram em `agenda/`: o card
 * (CardSimples), o menu (menuDaAgenda), o cabeçalho, as chamadas à API (apiDaAgenda), a
 * carga do mês (useSemanasDoMes) e as medidas da grade (useMedidasDaGrade).
 */
export default function AgendaSimples() {
  const { usuario } = useAuth()
  const vendedores = useVendedores()
  const legenda = useLegenda()
  const [mes, setMes] = useState<Periodo>(() => mesDe(hojeIso()))
  const [erroAcao, setErroAcao] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

  const [selecionado, setSelecionado] = useState<Agendamento | null>(null)
  const [novaCelula, setNovaCelula] = useState<AlvoSolte | null>(null)
  const [selecao, setSelecao] = useState<Selecao>(null)
  /**
   * Vários cards escolhidos de uma vez, como no Excel: Shift+clique pega o retângulo entre o
   * card selecionado e o clicado; Ctrl+clique põe ou tira um card. Vazio quando há um só.
   */
  const [grupo, setGrupo] = useState<number[]>([])
  const grupoRef = useRef(grupo)
  grupoRef.current = grupo
  /** O mover do grupo é montado mais abaixo; o soltar, que vem antes, chega nele por aqui. */
  const moverGrupoRef = useRef<((arrastado: Agendamento, alvo: AlvoSolte) => void) | null>(null)
  const [arrastando, setArrastando] = useState<Agendamento | null>(null)
  const [alvo, setAlvo] = useState<string | null>(null)
  const [copiado, setCopiado] = useState<Agendamento | null>(null)
  const [recortado, setRecortado] = useState(false)
  const [menu, setMenu] = useState<{ x: number; y: number; item?: Agendamento; celula?: AlvoSolte } | null>(null)
  const [editando, setEditando] = useState<{ item?: Agendamento; celula?: AlvoSolte; texto: string } | null>(null)
  /** O que está sendo editado na agenda: adesivadores, status ou vendedores. */
  const [editandoColunas, setEditandoColunas] = useState<false | EdicaoDaAgenda>(false)
  /** Serviço do Noturno cujos adesivadores estão sendo escolhidos. */
  const [atribuindo, setAtribuindo] = useState<Agendamento | null>(null)
  /** Card no tamanho novo enquanto a borda está sendo puxada. */
  const [previa, setPrevia] = useState<(PreviaSimples & { semanaInicio: string }) | null>(null)
  /**
   * Área escolhida arrastando o mouse pela grade, como no Excel: um retângulo de linhas
   * (o mês é uma régua só, semana após semana) e colunas. Os cards dentro dela viram o
   * grupo; só com espaços vazios, a tecla **N** marca todos como indisponível.
   */
  const [area, setArea] = useState<{ de: PontoDoMes; ate: PontoDoMes } | null>(null)
  const areaRef = useRef(area)
  areaRef.current = area
  /** Onde o botão do mouse desceu; a área só nasce quando ele passa para outra célula. */
  const ancoraDaArea = useRef<PontoDoMes | null>(null)

  /** Quantas linhas a área escolhida tem (1 quando não há área). */
  const espacosDaFaixa = area ? Math.abs(area.ate.linha - area.de.linha) + 1 : 1

  const ehSegundoToque = useSegundoToque()
  /** Puxar a borda solta um clique logo depois; esta janela curta o ignora. */
  const ignorarCliqueAte = useRef(0)

  /** Alguém mexendo na agenda: a atualização automática espera a vez (veja useSemanasDoMes). */
  const mexendo = !!(
    arrastando ||
    previa ||
    menu ||
    editando ||
    selecionado ||
    novaCelula ||
    area ||
    editandoColunas ||
    atribuindo
  )
  const { semanas, erro, carregando, carregar } = useSemanasDoMes(mes, mexendo)

  const podeEditar =
    pode(usuario, 'agendaEditar')

  const falhou = (padrao: string) => (e: unknown) => setErroAcao(e instanceof Error ? e.message : padrao)

  /** O dia de hoje, marcado na grade. */
  const hoje = hojeIso()

  const prontas: SemanaPronta[] = useMemo(
    () =>
      (semanas ?? []).map((semana) => ({
        semana,
        linhas: montarLinhasSimples(semana),
        layout: montarLayoutSimples(semana, previa?.semanaInicio === semana.inicio ? previa : null),
      })),
    [semanas, previa],
  )

  /** As colunas do mês, por id: o tipo de cada uma (Noturno) e os adesivadores para atribuir. */
  const colunasPorId = useMemo(() => {
    const mapa = new Map<number, Adesivador>()
    prontas.forEach((p) => p.semana.colunas.forEach((c) => mapa.set(c.id, c)))
    return mapa
  }, [prontas])

  const quadroRef = useRef<HTMLDivElement | null>(null)
  const topos = useToposDasLinhas(quadroRef)
  const pedirIrParaHoje = useIrParaHoje(quadroRef, prontas)
  useNomesQueCabem(quadroRef, prontas, editando)

  /** A semana em que cai um dia. */
  const semanaDoDia = useCallback(
    (data: string) => prontas.find((p) => p.semana.dias.some((d) => d.data === data)),
    [prontas],
  )

  /**
   * Quantos espaços de trabalho o serviço ocupa hoje, onde ele está.
   *
   * Por baixo, cada espaço vale um tanto de horas diferente (o 1º do dia vale 1h30, o 2º
   * vale 2h). Guardar as **horas** ao mover mudava o tamanho do card de lugar para lugar:
   * um serviço de um espaço virava dois. O que se guarda é a quantidade de espaços.
   */
  const espacosDoItem = useCallback(
    (item: Agendamento) => {
      const p = semanaDoDia(item.data)
      if (!p) return 1
      const linha = linhaDoInicio(p.semana, item.data, item.slotInicio)
      if (linha < 0) return 1
      return pedacosDeHoras(p.semana, linha, item.horasEstimadas)
    },
    [semanaDoDia],
  )

  /** Linha da grade de uma célula, e a semana dela. */
  const ondeCai = useCallback(
    (alvo: AlvoSolte) => {
      const p = semanaDoDia(alvo.data)
      if (!p) return null
      const linha = p.linhas.findIndex((l) => l.data === alvo.data && l.faixaInicio === alvo.slot)
      return linha < 0 ? null : { semana: p, linha }
    },
    [semanaDoDia],
  )

  /** As horas que esses espaços valem no destino, sem passar do espaço livre. */
  const horasNoDestino = useCallback(
    (destino: AlvoSolte, espacos: number, ignorarId?: number) => {
      const p = semanaDoDia(destino.data)
      if (!p) return null
      const linha = p.linhas.findIndex((l) => l.data === destino.data && l.faixaInicio === destino.slot)
      if (linha < 0) return null
      const livres = pedacosLivresApartirDe(p.layout.get(destino.adesivadorId), linha, p.linhas.length, ignorarId)
      if (livres === 0) return null // a célula é de outro serviço: soltar ali é trocar de lugar
      const cabem = Math.max(1, Math.min(espacos, livres))
      return { horas: horasDeIntervalo(p.semana, linha, linha + cabem - 1), encolheu: cabem < espacos }
    },
    [semanaDoDia],
  )

  /**
   * Soltar um carro: nesta agenda **ninguém é empurrado**. Se o serviço não cabe no buraco,
   * ele mesmo é encolhido até caber. Soltar em cima de outro continua trocando os dois.
   */
  const soltar = useCallback(
    ({ data, adesivadorId, slot }: AlvoSolte) => {
      const item = arrastando
      setArrastando(null)
      setAlvo(null)
      if (!item) return
      if (item.data === data && item.adesivadorId === adesivadorId && item.slotInicio === slot) return
      // Card de um grupo escolhido com Shift: o grupo inteiro vai junto.
      if (grupoRef.current.length > 1 && grupoRef.current.includes(item.id)) {
        moverGrupoRef.current?.(item, { data, adesivadorId, slot })
        return
      }
      setErroAcao(null)
      const destino = ondeCai({ data, adesivadorId, slot })
      const origem = ondeCai({ data: item.data, adesivadorId: item.adesivadorId, slot: item.slotInicio })
      const ocupante = destino?.semana.layout
        .get(adesivadorId)
        ?.inicios.get(destino.linha)
        ?.itens.find((i) => i.id !== item.id)

      /**
       * Soltar em cima de outro troca os dois de lugar. Cada um leva **quantos espaços
       * ocupa**, não as horas: sem isso, um serviço de um espaço que caísse num pedaço
       * mais curto virava dois lá.
       */
      const paraOTroco = () => {
        if (!ocupante || !destino || !origem) return null
        const espacosDoArrastado = espacosDoItem(item)
        const espacosDoOutro = espacosDoItem(ocupante)
        // Cada um cabe no que o outro ocupava, mais o que estiver livre logo abaixo.
        const espacoLa =
          espacosDoOutro +
          pedacosLivresApartirDe(
            destino.semana.layout.get(adesivadorId),
            destino.linha + espacosDoOutro,
            destino.semana.linhas.length,
            ocupante.id,
          )
        const espacoCa =
          espacosDoArrastado +
          pedacosLivresApartirDe(
            origem.semana.layout.get(item.adesivadorId),
            origem.linha + espacosDoArrastado,
            origem.semana.linhas.length,
            item.id,
          )
        return {
          arrastado: horasDeIntervalo(
            destino.semana.semana,
            destino.linha,
            destino.linha + Math.max(1, Math.min(espacosDoArrastado, espacoLa)) - 1,
          ),
          outro: horasDeIntervalo(
            origem.semana.semana,
            origem.linha,
            origem.linha + Math.max(1, Math.min(espacosDoOutro, espacoCa)) - 1,
          ),
        }
      }

      const troco = paraOTroco()
      const no = troco ? null : horasNoDestino({ data, adesivadorId, slot }, espacosDoItem(item), item.id)

      /**
       * O tamanho vai junto com o mover, e não numa chamada antes.
       *
       * Mexer nas horas com o card ainda no lugar velho dava outro tanto de faixas ali, e
       * o servidor leva as **faixas** consigo: um serviço de um espaço chegava do outro
       * lado ocupando dois. Mandando as horas do destino, a conta é feita já no lugar novo.
       */
      apiDaAgenda
        .mover(item.id, {
          data,
          adesivadorId,
          slotInicio: slot,
          ...(troco ? { horasEstimadas: troco.arrastado, horasDoOcupante: troco.outro } : {}),
          ...(no ? { horasEstimadas: no.horas } : {}),
        })
        .then(() => {
          setAviso(no?.encolheu ? `"${item.descricao}" foi ajustado para caber no espaço livre.` : null)
          carregar()
        })
        .catch(falhou('Não foi possível mover.'))
    },
    [arrastando, espacosDoItem, horasNoDestino, ondeCai, carregar],
  )

  const colarEm = useCallback(
    ({ data, adesivadorId, slot }: AlvoSolte) => {
      const origem = copiado
      if (!origem) return
      setErroAcao(null)
      // Com vários espaços escolhidos, o colado ocupa a faixa inteira; com um, mantém o
      // tamanho do original.
      const pedidos = espacosDaFaixa > 1 ? espacosDaFaixa : espacosDoItem(origem)
      const no = horasNoDestino({ data, adesivadorId, slot }, pedidos, recortado ? origem.id : undefined)
      const lugar = { data, adesivadorId, slotInicio: slot, horasEstimadas: no?.horas ?? origem.horasEstimadas }

      if (recortado) {
        // O tamanho vai junto: no lugar novo as faixas são outras (veja o soltar).
        apiDaAgenda
          .mover(origem.id, lugar)
          .then(() => {
            setCopiado(null)
            setRecortado(false)
            setSelecao(null)
            setAviso(`"${origem.descricao}" movido. Ctrl+Z desfaz.`)
            carregar()
          })
          .catch(falhou('Não foi possível mover.'))
        return
      }

      // Colar não duplica: cria mais uma **parte do mesmo serviço**. Nome, vendedor, OS e
      // estado continuam sendo os mesmos nas duas; só o lugar e o tamanho são de cada uma.
      apiDaAgenda
        .novaParte(origem.id, lugar)
        .then(() => {
          setAviso(`"${origem.descricao}" copiado aqui — é o mesmo serviço (editar um muda todos).`)
          carregar()
        })
        .catch(falhou('Não foi possível colar.'))
    },
    [copiado, recortado, carregar, espacosDoItem, horasNoDestino, espacosDaFaixa],
  )

  const desfazer = useCallback(() => {
    setErroAcao(null)
    apiDaAgenda
      .desfazer()
      .then((r) => {
        setAviso(`Desfeito: ${r.desfeito}.`)
        carregar()
      })
      .catch(falhou('Não foi possível desfazer.'))
  }, [carregar])

  const excluir = useCallback(
    (item: Agendamento) => {
      setErroAcao(null)
      setSelecao(null)
      apiDaAgenda
        .excluir(item.id)
        .then(() => {
          setAviso(`"${item.descricao}" excluído. Ctrl+Z traz de volta.`)
          carregar()
        })
        .catch(falhou('Não foi possível excluir.'))
    },
    [carregar],
  )

  /**
   * O mês como uma régua só: todas as linhas, semana após semana (a semana seguinte
   * continua a anterior), e as colunas na ordem da tela. Base do Shift+clique e de
   * arrastar vários cards juntos.
   */
  const { linhasDoMes, colunasDoMes, posicoesDosCards, baseDaSemana } = useMemo(() => {
    const colunas: number[] = []
    prontas.forEach((p) => p.semana.colunas.forEach((c) => colunas.includes(c.id) || colunas.push(c.id)))
    const linhas: { semana: SemanaPronta; indice: number }[] = []
    const lista: { item: Agendamento; linha: number; coluna: number; span: number }[] = []
    const bases = new Map<string, number>()
    prontas.forEach((p) => {
      const base = linhas.length
      bases.set(p.semana.inicio, base)
      p.linhas.forEach((_, indice) => linhas.push({ semana: p, indice }))
      p.layout.forEach((daColuna, colunaId) =>
        daColuna.inicios.forEach(({ itens, span }, linha) =>
          itens.forEach((item) =>
            lista.push({ item, linha: base + linha, coluna: colunas.indexOf(colunaId), span }),
          ),
        ),
      )
    })
    return { linhasDoMes: linhas, colunasDoMes: colunas, posicoesDosCards: lista, baseDaSemana: bases }
  }, [prontas])

  /** Os cards do retângulo entre dois cards (linhas e colunas entre eles), como no Excel. */
  const cardsEntre = useCallback(
    (a: Agendamento, b: Agendamento) => {
      const pa = posicoesDosCards.find((p) => p.item.id === a.id)
      const pb = posicoesDosCards.find((p) => p.item.id === b.id)
      if (!pa || !pb) return [a.id, b.id]
      const [l1, l2] = [Math.min(pa.linha, pb.linha), Math.max(pa.linha, pb.linha)]
      const [c1, c2] = [Math.min(pa.coluna, pb.coluna), Math.max(pa.coluna, pb.coluna)]
      const ids = posicoesDosCards
        .filter((p) => p.linha >= l1 && p.linha <= l2 && p.coluna >= c1 && p.coluna <= c2)
        .map((p) => p.item.id)
      return [...new Set(ids)]
    },
    [posicoesDosCards],
  )

  /**
   * A área escolhida vira a seleção: os cards dentro dela formam o grupo (o primeiro fica
   * selecionado, para as letras de status e o menu); sem cards, a célula do canto de cima
   * fica selecionada — é onde o Ctrl+V cola.
   */
  useEffect(() => {
    if (!area) return
    const { l1, l2, c1, c2 } = limitesDa(area)
    const dentro = posicoesDosCards
      .filter((x) => x.linha <= l2 && x.linha + x.span - 1 >= l1 && x.coluna >= c1 && x.coluna <= c2)
      .sort((a, b) => a.linha - b.linha || a.coluna - b.coluna)
    const ids = [...new Set(dentro.map((x) => x.item.id))]
    setGrupo((g) => (g.length === ids.length && g.every((id, i) => id === ids[i]) ? g : ids))
    if (dentro.length) {
      const item = dentro[0].item
      setSelecao((s) => (s?.tipo === 'card' && s.item === item ? s : { tipo: 'card', item }))
      return
    }
    const naRegua = linhasDoMes[l1]
    const linha = naRegua?.semana.linhas[naRegua.indice]
    if (!linha) return
    const nova = { tipo: 'celula' as const, data: linha.data, adesivadorId: colunasDoMes[c1], slot: linha.faixaInicio }
    setSelecao((s) =>
      s?.tipo === 'celula' && s.data === nova.data && s.adesivadorId === nova.adesivadorId && s.slot === nova.slot
        ? s
        : nova,
    )
  }, [area, posicoesDosCards, linhasDoMes, colunasDoMes])

  /** Arrastando o mouse com o botão apertado: a área vai da célula de origem até esta. */
  useEffect(() => {
    const mover = (e: PointerEvent) => {
      const ancora = ancoraDaArea.current
      if (!ancora || !(e.buttons & 1)) return
      const ponto = pontoSob(document.elementFromPoint(e.clientX, e.clientY), e.clientY)
      if (!ponto) return
      setArea((a) => {
        if (!a && ponto.linha === ancora.linha && ponto.coluna === ancora.coluna) return a
        if (a && a.ate.linha === ponto.linha && a.ate.coluna === ponto.coluna) return a
        return { de: ancora, ate: ponto }
      })
    }
    const soltar = () => {
      // Soltar o mouse depois de escolher não pode valer como clique (que desfaria a área).
      if (ancoraDaArea.current && areaRef.current) ignorarCliqueAte.current = Date.now() + 300
      ancoraDaArea.current = null
    }
    window.addEventListener('pointermove', mover)
    window.addEventListener('pointerup', soltar)
    return () => {
      window.removeEventListener('pointermove', mover)
      window.removeEventListener('pointerup', soltar)
    }
  }, [])

  // Clicar fora dos cards (num espaço vazio, Esc) desfaz o grupo.
  useEffect(() => {
    if (selecao?.tipo !== 'card') setGrupo([])
  }, [selecao])

  /**
   * Uma marcação de cada vez: clicar num card tira a dos espaços vazios, clicar num espaço
   * vazio tira a do card, e clicar em qualquer outro lugar (fora da grade) tira as duas.
   * O menu do botão direito e as janelas abertas não contam como "fora": agem sobre o que
   * está marcado. O que foi copiado continua copiado.
   */
  useEffect(() => {
    const aoApertar = (e: PointerEvent) => {
      const onde = e.target as HTMLElement | null
      // A alça de mover leva a seleção inteira junto: segurar nela não desfaz nada.
      if (!onde?.closest || onde.closest('.menu-contexto, .modal-fundo, .alca-arrastar')) return
      // O botão direito dentro da área abre o menu para ela.
      if (e.button === 2 && onde.closest('td.selecionada, .card-simples.selecionado')) return
      setArea(null)
      if (onde.closest('.card-simples')) return
      if (onde.closest('td.vazia')) {
        setSelecao((s) => (s?.tipo === 'card' ? null : s))
        return
      }
      setSelecao(null)
      setGrupo([])
    }
    document.addEventListener('pointerdown', aoApertar)
    return () => document.removeEventListener('pointerdown', aoApertar)
  }, [])

  /** Muda o estado de todos os cards do grupo, com um Ctrl+Z só. */
  const mudarStatusDoGrupo = useCallback(
    (status: StatusAgendamento, etiquetaId: number | null = null) => {
      setErroAcao(null)
      apiDaAgenda
        .statusEmLote(grupo, status, etiquetaId)
        .then((r) => {
          setAviso(`${r.cards} card(s): ${rotuloStatusAgenda(status, etiquetaId)}. Ctrl+Z desfaz.`)
          carregar()
        })
        .catch(falhou('Não foi possível mudar o estado dos cards.'))
    },
    [grupo, carregar],
  )

  /**
   * Arrasta o grupo inteiro: todos andam o mesmo tanto que o card arrastado — as mesmas
   * linhas para cima ou para baixo e as mesmas colunas para o lado —, cada um com o seu
   * tamanho (em espaços). Se algum sairia do mês na tela ou cairia em cima de um card de
   * fora, nada se move.
   */
  const moverGrupo = useCallback(
    (arrastado: Agendamento, alvo: AlvoSolte) => {
      const destino = ondeCai(alvo)
      const deOnde = posicoesDosCards.find((x) => x.item.id === arrastado.id)
      if (!destino || !deOnde) return
      const inicioDaSemana = linhasDoMes.findIndex((l) => l.semana === destino.semana)
      const deltaLinha = inicioDaSemana + destino.linha - deOnde.linha
      const deltaColuna = colunasDoMes.indexOf(alvo.adesivadorId) - deOnde.coluna
      if (deltaLinha === 0 && deltaColuna === 0) return

      const itens = []
      for (const id of new Set(grupo)) {
        const aqui = posicoesDosCards.find((x) => x.item.id === id)
        if (!aqui) continue
        const linha = aqui.linha + deltaLinha
        const coluna = aqui.coluna + deltaColuna
        if (linha < 0 || linha >= linhasDoMes.length || coluna < 0 || coluna >= colunasDoMes.length) {
          setAviso(`"${aqui.item.descricao}" sairia da agenda deste mês. Nada foi movido.`)
          return
        }
        // O tamanho em espaços se mantém; as horas são as dos espaços do lugar novo.
        const espacos = Math.min(espacosDoItem(aqui.item), linhasDoMes.length - linha)
        let horas = 0
        for (let k = 0; k < espacos; k++) {
          const l = linhasDoMes[linha + k]
          horas += horasDeIntervalo(l.semana.semana, l.indice, l.indice)
        }
        const lugar = linhasDoMes[linha]
        const linhaDaGrade = lugar.semana.linhas[lugar.indice]
        itens.push({
          id,
          data: linhaDaGrade.data,
          adesivadorId: colunasDoMes[coluna],
          slotInicio: linhaDaGrade.faixaInicio,
          horasEstimadas: horas,
        })
      }

      setErroAcao(null)
      apiDaAgenda
        .moverEmLote(itens)
        .then((r) => {
          setAviso(`${r.cards} cards movidos juntos. Ctrl+Z desfaz.`)
          carregar()
        })
        .catch(falhou('Não foi possível mover os cards.'))
    },
    [ondeCai, posicoesDosCards, linhasDoMes, colunasDoMes, grupo, espacosDoItem, carregar],
  )
  moverGrupoRef.current = moverGrupo

  /** Exclui todos os cards do grupo; um Ctrl+Z traz todos de volta. */
  const excluirGrupo = useCallback(() => {
    const ids = grupo
    setErroAcao(null)
    setSelecao(null)
    setGrupo([])
    apiDaAgenda
      .excluirEmLote(ids)
      .then((r) => {
        setAviso(`${r.cards} cards excluídos. Ctrl+Z traz todos de volta.`)
        carregar()
      })
      .catch(falhou('Não foi possível excluir os cards.'))
  }, [grupo, carregar])

  /**
   * Marca de uma vez os espaços escolhidos como **indisponíveis** (férias, falta,
   * atestado…). É o "N" da planilha antiga, onde a pessoa escrevia N na coluna do dia.
   */
  const marcarIndisponivel = useCallback(() => {
    // A área escolhida; sem área, a célula vazia selecionada.
    let limites = area ? limitesDa(area) : null
    if (!limites && selecao?.tipo === 'celula') {
      const linha = linhasDoMes.findIndex((l) => {
        const x = l.semana.linhas[l.indice]
        return x.data === selecao.data && x.faixaInicio === selecao.slot
      })
      const coluna = colunasDoMes.indexOf(selecao.adesivadorId)
      if (linha >= 0 && coluna >= 0) limites = { l1: linha, l2: linha, c1: coluna, c2: coluna }
    }
    if (!limites) return
    // Como os outros itens repetidos: um bloco de um espaço em cada linha livre.
    const lugares = []
    for (let c = limites.c1; c <= limites.c2; c++) {
      const colunaId = colunasDoMes[c]
      for (let l = limites.l1; l <= limites.l2; l++) {
        const { semana: s, indice } = linhasDoMes[l]
        if (!s.semana.colunas.some((x) => x.id === colunaId)) continue
        const daColuna = s.layout.get(colunaId)
        if (daColuna?.inicios.has(indice) || daColuna?.cobertas.has(indice)) continue
        const linha = s.linhas[indice]
        lugares.push({
          data: linha.data,
          adesivadorId: colunaId,
          slotInicio: linha.faixaInicio,
          horasEstimadas: horasDeIntervalo(s.semana, indice, indice),
        })
      }
    }
    if (!lugares.length) {
      setAviso('Esses espaços já são de serviços. Escolha espaços livres.')
      return
    }
    setErroAcao(null)
    setArea(null)
    apiDaAgenda
      .marcarIndisponivel(lugares)
      .then((r) => {
        setAviso(`${r.cards} espaço(s) marcados como indisponível. Ctrl+Z desfaz.`)
        carregar()
      })
      .catch(falhou('Não foi possível marcar como indisponível.'))
  }, [area, selecao, linhasDoMes, colunasDoMes, carregar])

  /** Troca o vendedor do carro pelo menu do botão direito. */
  const definirVendedor = useCallback(
    (item: Agendamento, codigo: string | null) => {
      setErroAcao(null)
      apiDaAgenda
        .atualizar(item, { vendedorCodigo: codigo })
        .then(() => {
          setAviso(`"${item.descricao}": ${codigo ? rotuloVendedor(codigo) : 'sem vendedor'}.`)
          carregar()
        })
        .catch(falhou('Não foi possível trocar o vendedor.'))
    },
    [carregar],
  )

  const mudarStatus = useCallback(
    (item: Agendamento, status: StatusAgendamento, etiquetaId: number | null = null) => {
      setErroAcao(null)
      apiDaAgenda
        .mudarStatus(item.id, status, etiquetaId)
        .then(() => {
          setAviso(`"${item.descricao}": ${rotuloStatusAgenda(status, etiquetaId)}.`)
          carregar()
        })
        .catch(falhou('Não foi possível mudar o estado do carro.'))
    },
    [carregar],
  )

  /** Salva o nome digitado na célula: renomeia o carro ou cria um novo ali. */
  const salvarEdicao = useCallback(() => {
    const atual = editando
    setEditando(null)
    if (!atual) return
    const texto = atual.texto.trim()
    if (!texto) return
    setErroAcao(null)

    if (atual.item) {
      if (texto === atual.item.descricao) return
      apiDaAgenda
        .atualizar(atual.item, { descricao: texto })
        .then(() => carregar())
        .catch(falhou('Não foi possível renomear.'))
      return
    }

    const celula = atual.celula!
    const p = semanaDoDia(celula.data)
    const linha = p?.linhas.findIndex((l) => l.data === celula.data && l.faixaInicio === celula.slot) ?? -1
    apiDaAgenda
      .criar({
        data: celula.data,
        adesivadorId: celula.adesivadorId,
        slotInicio: celula.slot,
        // Um espaço só: as horas das faixas daquele pedaço, nem uma a mais.
        horasEstimadas: p && linha >= 0 ? horasDeIntervalo(p.semana, linha, linha) : 1.5,
        descricao: texto,
        status: 'PROGRAMADO',
      })
      .then(() => carregar())
      .catch(falhou('Não foi possível criar o serviço.'))
  }, [editando, carregar, semanaDoDia])

  useAtalhos({
    habilitado: podeEditar && !selecionado && !novaCelula && !editando && !menu && !editandoColunas && !atribuindo,
    selecao,
    copiado,
    aoCopiar: (item) => {
      if (grupo.length > 1) {
        setAviso('Copiar e recortar funcionam com um card só: clique num card sem o Shift.')
        return
      }
      setCopiado(item)
      setRecortado(false)
      setAviso(`"${item.descricao}" copiado. Cole num espaço livre: vira uma cópia ligada (editar uma muda todas).`)
    },
    aoRecortar: (item) => {
      if (grupo.length > 1) {
        setAviso('Copiar e recortar funcionam com um card só: clique num card sem o Shift.')
        return
      }
      setCopiado(item)
      setRecortado(true)
      setAviso(`"${item.descricao}" recortado. Clique num espaço livre e cole: ele sai de onde está.`)
    },
    aoColar: colarEm,
    aoDesfazer: desfazer,
    // Com vários escolhidos, o Delete apaga todos (e um Ctrl+Z traz todos de volta).
    aoExcluir: (item) => (grupo.length > 1 ? excluirGrupo() : excluir(item)),
    aoLimpar: () => {
      setSelecao(null)
      setArea(null)
      setCopiado(null)
      setRecortado(false)
      setAviso(null)
    },
    aoAvisar: setAviso,
  })

  useEffect(() => {
    if (!podeEditar) return
    function aoTeclar(e: KeyboardEvent) {
      const alvo = e.target as HTMLElement
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(alvo.tagName)) return
      if (selecionado || novaCelula || editando || menu || editandoColunas || atribuindo) return
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const tecla = e.key.toLowerCase()

      // Com vários cards escolhidos, a letra troca o estado de todos.
      if (grupo.length > 1) {
        const status = TECLAS_DE_STATUS[tecla]
        if (!status) return
        e.preventDefault()
        mudarStatusDoGrupo(status)
        return
      }

      // Com um carro selecionado, a letra troca o estado dele.
      if (selecao?.tipo === 'card') {
        const status = TECLAS_DE_STATUS[tecla]
        if (
          !status ||
          selecao.item.tipo === 'INDISPONIVEL' ||
          (selecao.item.status === status && !selecao.item.etiquetaId)
        )
          return
        e.preventDefault()
        mudarStatus(selecao.item, status)
        return
      }

      // Com espaços vazios escolhidos, o N marca o bloqueio.
      if (tecla === 'n' && (area || selecao?.tipo === 'celula')) {
        e.preventDefault()
        marcarIndisponivel()
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [
    podeEditar,
    area,
    selecao,
    selecionado,
    novaCelula,
    editando,
    menu,
    editandoColunas,
    marcarIndisponivel,
    mudarStatus,
    grupo,
    mudarStatusDoGrupo,
  ])

  const aoTocarCard = useArrasteToque({
    habilitado: podeEditar,
    aoIniciar: (item) => {
      setErroAcao(null)
      setArrastando(item)
    },
    aoPassarPor: setAlvo,
    aoSoltar: (destino) => {
      if (destino) soltar(destino)
      else {
        setArrastando(null)
        setAlvo(null)
      }
    },
  })

  const puxarBorda = useRedimensionarBlocos({
    habilitado: podeEditar,
    aoMudarPrevia: setPrevia,
    aoTerminar: () => {
      ignorarCliqueAte.current = Date.now() + 400
    },
    aoConfirmar: (puxada, linhaFim) => {
      const p = prontas.find((x) => x.semana.inicio === puxada.semanaInicio)
      if (!p) return
      setErroAcao(null)
      // Puxar para cima só serve para encolher um card antigo, de vários espaços.
      if (linhaFim < puxada.linhaFim) {
        apiDaAgenda
          .mudarHoras(puxada.id, horasDeIntervalo(p.semana, puxada.linhaInicio, linhaFim))
          .then(() => carregar())
          .catch(falhou('Não foi possível mudar a duração.'))
        return
      }
      /**
       * Puxar para baixo **não estica** o card: replica o mesmo serviço em cada espaço até
       * onde a alça foi solta. Cada cópia ocupa um espaço e é parte do mesmo serviço —
       * nome, OS, vendedor e estado andam juntos.
       */
      const original = posicoesDosCards.find((x) => x.item.id === puxada.id)?.item
      if (!original || linhaFim <= puxada.linhaFim) return
      const destinos = []
      for (let linha = puxada.linhaFim + 1; linha <= linhaFim; linha++) {
        const l = p.linhas[linha]
        destinos.push({
          data: l.data,
          adesivadorId: original.adesivadorId,
          slotInicio: l.faixaInicio,
          horasEstimadas: horasDeIntervalo(p.semana, linha, linha),
        })
      }
      apiDaAgenda
        .replicar(puxada.id, destinos)
        .then((r) => {
          setAviso(
            `"${original.descricao}" replicado em ${r.cards} espaço${r.cards === 1 ? '' : 's'}: é o mesmo serviço (editar um muda todos). Ctrl+Z desfaz.`,
          )
          carregar()
        })
        .catch(falhou('Não foi possível replicar o card.'))
    },
  })

  if (carregando && !semanas) return <Carregando texto="Carregando agenda..." />
  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!semanas?.length) return null

  const menuAberto =
    menu &&
    itensDoMenu(
      {
        podeEditar,
        grupo,
        statusDoMenu: opcoesDeStatus(legenda),
        vendedores,
        copiado,
        recortado,
        renomear: (item) => setEditando({ item, texto: item.descricao }),
        abrir: setSelecionado,
        ehNoturno: (item) => colunasPorId.get(item.adesivadorId)?.tipo === 'NOTURNO',
        atribuir: setAtribuindo,
        mudarStatus,
        mudarStatusDoGrupo,
        definirVendedor,
        copiar: (item) => {
          setCopiado(item)
          setRecortado(false)
          setAviso(`"${item.descricao}" copiado. Cole num espaço livre: vira uma cópia ligada (editar uma muda todas).`)
        },
        recortar: (item) => {
          setCopiado(item)
          setRecortado(true)
          setAviso(`"${item.descricao}" recortado. Clique num espaço livre e cole.`)
        },
        excluir,
        excluirGrupo,
        novoAqui: (celula) => setEditando({ celula, texto: '' }),
        novoComDetalhes: setNovaCelula,
        colarEm,
        desfazer,
      },
      menu,
    )

  return (
    <div className="pagina-simples">
      <CabecalhoDaAgenda
        mes={mes}
        aoTrocarMes={setMes}
        aoIrParaHoje={pedirIrParaHoje}
        podeEditarLegenda={pode(usuario, 'colunasAgenda')}
        aoEditar={setEditandoColunas}
        podeEditar={podeEditar}
        aoDesfazer={desfazer}
      />

      {aviso && <Aviso tipo="info">{aviso}</Aviso>}
      {erroAcao && <Aviso tipo="erro">{erroAcao}</Aviso>}

      <div
        ref={quadroRef}
        // Clicar e arrastar escolhe células (menos na alça de mover e na do canto).
        onPointerDown={(e) => {
          ancoraDaArea.current = null
          if (e.button !== 0 || e.pointerType === 'touch' || e.shiftKey || e.ctrlKey || e.metaKey) return
          const onde = e.target as HTMLElement
          if (onde.closest('.alca-arrastar, .punho-canto, input, button')) return
          ancoraDaArea.current = pontoSob(onde, e.clientY)
        }}
      >
        {prontas.map(({ semana, linhas, layout }, indiceSemana) => (
          <div className="quadro-simples" key={semana.inicio}>
            <div className="titulo-semana">
              <strong>Semana {indiceSemana + 1}</strong> · {formatarDia(semana.inicio)} a{' '}
              {formatarDia(semana.fim)}
            </div>
            <table className={`grade-simples${arrastando ? ' arrastando' : ''}`}>
              <thead>
                <tr>
                  <th className="col-dia">Dia</th>
                  <th className="col-num" title="Os 5 espaços de trabalho do dia">
                    #
                  </th>
                  {semana.colunas.map((coluna) => {
                    const horas = semana.horasOcupadas.find((t) => t.adesivadorId === coluna.id)?.total ?? 0
                    const bloqueadas =
                      semana.horasIndisponiveis.find((t) => t.adesivadorId === coluna.id)?.total ?? 0
                    const disponivel = Math.max(0, semana.horasUteisDaSemana - bloqueadas)
                    return (
                      <th key={coluna.id}>
                        <span className="coluna-nome">{coluna.nome}</span>
                        <span className="coluna-carga">
                          {formatarHoras(horas)} / {formatarHoras(disponivel)}
                        </span>
                      </th>
                    )
                  })}
                </tr>
              </thead>
              <tbody data-semana={semana.inicio}>
                {linhas.map((linha, indiceLinha) => (
                  <tr key={indiceLinha} className={linha.ultimaDoDia ? 'fim-do-dia' : undefined}>
                    {linha.primeiraDoDia && (
                      <th
                        className={linha.data === hoje ? 'col-dia dia-hoje' : 'col-dia'}
                        rowSpan={BLOCOS_POR_DIA}
                        data-hoje={linha.data === hoje ? '' : undefined}
                      >
                        {linha.data === hoje && <span className="selo-hoje">Hoje</span>}
                        <span className="dia-nome">{linha.diaSemana.slice(0, 3)}</span>
                        <span className="dia-data">{formatarDia(linha.data)}</span>
                      </th>
                    )}
                    <th className="col-num">{linha.bloco}</th>

                    {semana.colunas.map((coluna) => {
                      const daColuna = layout.get(coluna.id)
                      const inicio = daColuna?.inicios.get(indiceLinha)
                      if (!inicio && daColuna?.cobertas.has(indiceLinha)) return null

                      const celula: AlvoSolte = {
                        data: linha.data,
                        adesivadorId: coluna.id,
                        slot: linha.faixaInicio,
                      }
                      const chave = chaveDoAlvo(linha.data, coluna.id, linha.faixaInicio)

                      if (!inicio) {
                        const editandoAqui =
                          editando?.celula?.data === linha.data &&
                          editando?.celula?.adesivadorId === coluna.id &&
                          editando?.celula?.slot === linha.faixaInicio
                        const naArea =
                          !!area &&
                          (() => {
                            const { l1, l2, c1, c2 } = limitesDa(area)
                            const l = (baseDaSemana.get(semana.inicio) ?? 0) + indiceLinha
                            const c = colunasDoMes.indexOf(coluna.id)
                            return l >= l1 && l <= l2 && c >= c1 && c <= c2
                          })()
                        const selecionada =
                          naArea ||
                          (selecao?.tipo === 'celula' &&
                            selecao.data === linha.data &&
                            selecao.adesivadorId === coluna.id &&
                            selecao.slot === linha.faixaInicio)
                        return (
                          <td
                            key={coluna.id}
                            data-alvo={chave}
                            data-linha={indiceLinha}
                            data-semana={semana.inicio}
                            data-linha-mes={(baseDaSemana.get(semana.inicio) ?? 0) + indiceLinha}
                            data-coluna-mes={colunasDoMes.indexOf(coluna.id)}
                            className={`vazia${alvo === chave ? ' alvo' : ''}${selecionada ? ' selecionada' : ''}`}
                            onDragOver={(e) => {
                              if (!arrastando) return
                              e.preventDefault()
                              setAlvo(chave)
                            }}
                            onDragLeave={() => setAlvo((a) => (a === chave ? null : a))}
                            onDrop={(e) => {
                              e.preventDefault()
                              soltar(celula)
                            }}
                            onClick={
                              podeEditar
                                ? () => {
                                    if (Date.now() < ignorarCliqueAte.current) return
                                    if (ehSegundoToque(`celula:${chave}`)) setEditando({ celula, texto: '' })
                                    else setSelecao({ tipo: 'celula', ...celula })
                                  }
                                : undefined
                            }
                            onContextMenu={(e) => {
                              e.preventDefault()
                              setSelecao({ tipo: 'celula', ...celula })
                              setMenu({ x: e.clientX, y: e.clientY, celula })
                            }}
                          >
                            {editandoAqui ? (
                              <input
                                className="campo-celula"
                                autoFocus
                                value={editando!.texto}
                                placeholder="Nome do carro/serviço"
                                onChange={(e) => setEditando({ celula, texto: e.target.value })}
                                onBlur={salvarEdicao}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter') salvarEdicao()
                                  if (e.key === 'Escape') setEditando(null)
                                }}
                              />
                            ) : (
                              podeEditar && <span className="mais">{copiado ? '📋' : '+'}</span>
                            )}
                          </td>
                        )
                      }

                      const { itens, span } = inicio
                      const primeiro = itens[0]
                      const livresAbaixo = pedacosLivresApartirDe(
                        daColuna,
                        indiceLinha + span,
                        linhas.length,
                        primeiro?.id,
                      )
                      return (
                        <td
                          key={coluna.id}
                          rowSpan={span}
                          data-alvo={chave}
                          data-linha={indiceLinha}
                          data-semana={semana.inicio}
                          data-linha-mes={(baseDaSemana.get(semana.inicio) ?? 0) + indiceLinha}
                          data-coluna-mes={colunasDoMes.indexOf(coluna.id)}
                          className={`ocupada${alvo === chave ? ' alvo' : ''}`}
                          onDragOver={(e) => {
                            if (!arrastando || itens.some((i) => i.id === arrastando.id)) return
                            e.preventDefault()
                            setAlvo(chave)
                          }}
                          onDragLeave={() => setAlvo((a) => (a === chave ? null : a))}
                          onDrop={(e) => {
                            e.preventDefault()
                            soltar(celula)
                          }}
                        >
                          {/* Os cards ficam por cima da célula e não a esticam: a linha tem
                              sempre a mesma altura. */}
                          <div className="celula-cards">
                            {itens.map((item) => {
                              const editandoEste = editando?.item?.id === item.id
                              return (
                                <CardSimples
                                  key={item.id}
                                  item={item}
                                  span={span}
                                  indiceLinha={indiceLinha}
                                  semanaInicio={semana.inicio}
                                  topos={topos[semana.inicio]}
                                  podeEditar={podeEditar}
                                  estado={{
                                    arrastavel: podeEditar && !editandoEste && !previa,
                                    selecionado:
                                      grupo.includes(item.id) ||
                                      (selecao?.tipo === 'card' && selecao.item.id === item.id),
                                    recortado: recortado && copiado?.id === item.id,
                                    copiaRealcada:
                                      selecao?.tipo === 'card' &&
                                      selecao.item.grupoId != null &&
                                      item.grupoId === selecao.item.grupoId,
                                    emArrasto:
                                      arrastando?.id === item.id ||
                                      (!!arrastando &&
                                        grupo.length > 1 &&
                                        grupo.includes(arrastando.id) &&
                                        grupo.includes(item.id)),
                                    emPrevia: previa?.id === item.id,
                                  }}
                                  textoEditado={editandoEste ? editando!.texto : null}
                                  aoMudarTexto={(texto) => setEditando({ item, texto })}
                                  aoSalvarTexto={salvarEdicao}
                                  aoCancelarTexto={() => setEditando(null)}
                                  aoIniciarArrasto={(e) => {
                                    setErroAcao(null)
                                    setArrastando(item)
                                    e.dataTransfer.effectAllowed = 'move'
                                    e.dataTransfer.setData('text/plain', String(item.id))
                                  }}
                                  aoTerminarArrasto={() => {
                                    setArrastando(null)
                                    setAlvo(null)
                                  }}
                                  aoClicar={(e) => {
                                    if (Date.now() < ignorarCliqueAte.current) return
                                    // Shift+clique: o retângulo entre o card selecionado e este.
                                    if (e.shiftKey && selecao?.tipo === 'card') {
                                      setGrupo(cardsEntre(selecao.item, item))
                                      return
                                    }
                                    // Ctrl+clique: põe ou tira este card do grupo.
                                    if (e.ctrlKey || e.metaKey) {
                                      const base = grupo.length
                                        ? grupo
                                        : selecao?.tipo === 'card'
                                          ? [selecao.item.id]
                                          : []
                                      setGrupo(
                                        base.includes(item.id) ? base.filter((id) => id !== item.id) : [...base, item.id],
                                      )
                                      if (selecao?.tipo !== 'card') setSelecao({ tipo: 'card', item })
                                      return
                                    }
                                    setGrupo([])
                                    if (ehSegundoToque(`card:${item.id}`)) {
                                      if (podeEditar) setEditando({ item, texto: item.descricao })
                                    } else setSelecao({ tipo: 'card', item })
                                  }}
                                  aoTocar={(e) => aoTocarCard(e, item)}
                                  aoAbrirMenu={(e) => {
                                    e.preventDefault()
                                    // Fora do grupo, o botão direito volta a valer só para este card.
                                    if (!grupo.includes(item.id)) {
                                      setGrupo([])
                                      setSelecao({ tipo: 'card', item })
                                    }
                                    setMenu({ x: e.clientX, y: e.clientY, item })
                                  }}
                                  aoPuxarCanto={(e) =>
                                    puxarBorda(e, {
                                      id: item.id,
                                      semanaInicio: semana.inicio,
                                      linhaInicio: indiceLinha,
                                      linhaFim: indiceLinha + span - 1,
                                      minima: indiceLinha,
                                      maxima: indiceLinha + span - 1 + livresAbaixo,
                                    })
                                  }
                                />
                              )
                            })}
                          </div>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>

      {menu && menuAberto && (
        <MenuContexto
          x={menu.x}
          y={menu.y}
          titulo={menu.item ? menu.item.descricao : 'Espaço livre'}
          itens={menuAberto}
          aoFechar={() => setMenu(null)}
        />
      )}

      {editandoColunas === 'adesivadores' && (
        <EditarAdesivadores
          aoFechar={() => {
            setEditandoColunas(false)
            carregar()
          }}
        />
      )}
      {editandoColunas === 'status' && <EditarStatus aoFechar={() => setEditandoColunas(false)} />}
      {editandoColunas === 'vendedores' && <EditarVendedores aoFechar={() => setEditandoColunas(false)} />}
      {atribuindo && (
        <AtribuirAdesivadores
          item={atribuindo}
          adesivadores={[...colunasPorId.values()].filter((c) => c.tipo === 'ADESIVADOR' && c.ativo)}
          aoFechar={() => setAtribuindo(null)}
          aoSalvar={(ids) => {
            const item = atribuindo
            setAtribuindo(null)
            setErroAcao(null)
            apiDaAgenda
              .atribuir(item.id, ids)
              .then((r) => {
                const nomes = (r.atribuidos ?? []).map((a) => a.nome)
                setAviso(
                  `"${item.descricao}": ${nomes.length ? `atribuído a ${nomes.join(', ')}` : 'sem adesivador atribuído'}. Ctrl+Z desfaz.`,
                )
                carregar()
              })
              .catch(falhou('Não foi possível atribuir os adesivadores.'))
          }}
        />
      )}

      {(selecionado || novaCelula) && (
        <DetalheAgendamento
          agendamento={selecionado}
          novaCelula={
            novaCelula && { data: novaCelula.data, adesivadorId: novaCelula.adesivadorId, slot: novaCelula.slot }
          }
          faixas={semanas[0].faixas}
          podeEditar={podeEditar}
          podeMudarStatus={podeEditar}
          semHorarios
          aoFechar={() => {
            setSelecionado(null)
            setNovaCelula(null)
          }}
          aoSalvar={() => {
            setSelecionado(null)
            setNovaCelula(null)
            carregar()
          }}
        />
      )}
    </div>
  )
}
