import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Fluxo, Setor, SetorNome, StatusFluxo } from '../api/tipos'
import {
  Aviso,
  Carregando,
  ChipSetor,
  ChipStatus,
  Vazio,
  rotuloSetor,
  formatarDataHora,
  formatarDuracao,
} from '../componentes/Ui'

const STATUS: { valor: '' | StatusFluxo; rotulo: string }[] = [
  { valor: '', rotulo: 'Todos os status' },
  { valor: 'AGUARDANDO_RECEBIMENTO', rotulo: 'Aguardando recebimento' },
  { valor: 'EM_PROCESSAMENTO', rotulo: 'Em processamento' },
  { valor: 'ENCERRADA', rotulo: 'Concluídas' },
  { valor: 'CANCELADA', rotulo: 'Canceladas' },
]

/** RF05 - consulta de OS: por número, cliente ou fluxo, e filtrando por status e pelo setor onde a OS está. */
export default function Consulta() {
  const navegar = useNavigate()
  const [termo, setTermo] = useState('')
  const [status, setStatus] = useState<'' | StatusFluxo>('')
  const [setor, setSetor] = useState<'' | SetorNome>('')
  const [setores, setSetores] = useState<Setor[]>([])
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState<string | null>(null)

  const buscar = useCallback(() => {
    setCarregando(true)
    setErro(null)
    const params = new URLSearchParams()
    if (termo.trim()) params.set('termo', termo.trim())
    if (status) params.set('status', status)
    if (setor) params.set('setor', setor)
    api
      .get<Fluxo[]>(`/fluxos${params.toString() ? `?${params}` : ''}`)
      .then(setFluxos)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha na consulta.'))
      .finally(() => setCarregando(false))
  }, [termo, status, setor])

  useEffect(() => {
    api.get<Setor[]>('/setores').then(setSetores).catch(() => setSetores([]))
  }, [])

  useEffect(() => {
    const id = setTimeout(buscar, 250)
    return () => clearTimeout(id)
  }, [buscar])

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>Consulta de OS</h1>
          <p>Todas as ordens de serviço do sistema.</p>
        </div>
      </div>

      <div className="barra-filtros">
        <input
          placeholder="Buscar por número da OS, cliente ou fluxo..."
          value={termo}
          onChange={(e) => setTermo(e.target.value)}
        />
        <select value={status} onChange={(e) => setStatus(e.target.value as '' | StatusFluxo)}>
          {STATUS.map((s) => (
            <option key={s.valor} value={s.valor}>
              {s.rotulo}
            </option>
          ))}
        </select>
        <select value={setor} onChange={(e) => setSetor(e.target.value as '' | SetorNome)}>
          <option value="">Todos os setores</option>
          {setores.map((s) => (
            <option key={s.id} value={s.nome}>
              {rotuloSetor(s.nome)}
            </option>
          ))}
        </select>
      </div>

      {erro && <Aviso tipo="erro">{erro}</Aviso>}

      <div className="cartao">
        {carregando ? (
          <Carregando />
        ) : fluxos.length === 0 ? (
          <Vazio>Nenhum fluxo encontrado para os filtros informados.</Vazio>
        ) : (
          <div className="tabela-rolagem">
            <table>
              <thead>
                <tr>
                  <th>OS</th>
                  <th>Fluxo</th>
                  <th>Cliente</th>
                  <th>Setor atual</th>
                  <th>Status</th>
                  <th>No setor há</th>
                  <th>Aberta em</th>
                </tr>
              </thead>
              <tbody>
                {fluxos.map((fluxo) => (
                  <tr
                    key={fluxo.id}
                    className="clicavel"
                    onClick={() => navegar(`/fluxos/${fluxo.id}`)}
                  >
                    <td>
                      <strong>{fluxo.numeroOsErp}</strong>
                    </td>
                    <td>{fluxo.identificadorFluxo}</td>
                    <td>{fluxo.cliente ?? '—'}</td>
                    <td>
                      <ChipSetor setor={fluxo.setorAtual} />
                    </td>
                    <td>
                      <ChipStatus status={fluxo.status} />
                    </td>
                    <td className="mono">
                      {fluxo.encerrado ? '—' : formatarDuracao(fluxo.segundosNoSetor)}
                    </td>
                    <td className="mono">{formatarDataHora(fluxo.criadoEm)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
