import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Backup } from '../api/tipos'
import { Aviso, Carregando, Vazio } from './Ui'

const tamanho = (bytes: number) =>
  bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`

/**
 * Cópias de segurança do banco. O sistema faz uma por dia útil às 12:30 e outra antes de
 * cada atualização; aqui dá para conferir que estão acontecendo e fazer uma na hora.
 */
export default function Backups() {
  const [backups, setBackups] = useState<Backup[] | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [fazendo, setFazendo] = useState(false)

  const carregar = useCallback(() => {
    api
      .get<Backup[]>('/admin/backups')
      .then(setBackups)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao listar os backups.'))
  }, [])

  useEffect(carregar, [carregar])

  function fazerAgora() {
    setErro(null)
    setOk(null)
    setFazendo(true)
    api
      .post<Backup>('/admin/backups')
      .then((feito) => {
        setOk(`Backup feito: ${feito.arquivo}`)
        carregar()
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Não foi possível fazer o backup.'))
      .finally(() => setFazendo(false))
  }

  return (
    <div className="cartao cartao-estreito" style={{ maxWidth: 640 }}>
      <p style={{ marginTop: 0 }}>
        O sistema guarda uma cópia do banco todo dia útil às 12:30 e antes de cada
        atualização, e mantém as 30 mais recentes em <code>backend\backups</code>. Para
        restaurar, pare o sistema e rode <code>restaurar-backup.ps1</code>.
      </p>
      {erro && <Aviso tipo="erro">{erro}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}
      <button className="botao" onClick={fazerAgora} disabled={fazendo}>
        {fazendo ? 'Fazendo backup...' : 'Fazer backup agora'}
      </button>

      <div className="espaco-topo">
        {backups === null ? (
          <Carregando texto="Listando backups..." />
        ) : backups.length === 0 ? (
          <Vazio>Nenhum backup ainda.</Vazio>
        ) : (
          <ul className="lista-backups">
            {backups.map((b) => (
              <li key={b.arquivo}>
                <span className="mono">{b.arquivo}</span>
                <span>
                  {new Date(b.criadoEm).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}
                  {' · '}
                  {tamanho(b.tamanhoBytes)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
