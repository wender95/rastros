import { useEffect, useState } from 'react'

/** A empresa que usa esta instalação do RastrOS (configurada no servidor). */
export interface Empresa {
  sistema: string
  nome: string
  logo: string | null
  /** O administrador pode "agir como" outra pessoa nesta instalação (fase de teste). */
  agirComo: boolean
}

const PADRAO: Empresa = { sistema: 'RastrOS', nome: 'RastrOS', logo: '/rastros-logo-branca.svg', agirComo: false }

/** Uma busca só por carga da página: o topo, o login e o título da aba usam a mesma. */
let pedido: Promise<Empresa> | null = null
function buscarEmpresa(): Promise<Empresa> {
  pedido ??= fetch('/api/empresa')
    .then((r) => (r.ok ? (r.json() as Promise<Empresa>) : PADRAO))
    .catch(() => PADRAO)
  return pedido
}

export function useEmpresa(): Empresa | null {
  const [empresa, setEmpresa] = useState<Empresa | null>(null)
  useEffect(() => {
    let ativo = true
    buscarEmpresa().then((e) => ativo && setEmpresa(e))
    return () => {
      ativo = false
    }
  }, [])
  return empresa
}

/** A empresa usuária: a logo dela (branca, para fundo escuro) ou, sem logo, o nome. */
export function MarcaEmpresa({ empresa }: { empresa: Empresa | null }) {
  if (!empresa) return null
  return empresa.logo ? (
    <img className="marca-empresa" src={empresa.logo} alt={empresa.nome} title={empresa.nome} />
  ) : (
    <span className="marca-empresa-nome">{empresa.nome}</span>
  )
}
