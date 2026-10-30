/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useForm } from 'react-hook-form'
import { 
  Save, 
  Plus, 
  Trash2, 
  Plug2,
  Settings2,
  ShieldCheck,
  Loader2,
  AlertCircle,
  Cpu,
  HardDrive,
  Database,
  Server,
  Search,
  Sun,
  Sparkles,
  Key,
  Network,
  Layers,
  Globe,
  Archive,
  Cloud
} from 'lucide-react'
import { useState, useEffect } from 'react'
import { connectorApi } from '../lib/api'
import VespaModelInsights from './VespaModelInsights'

type ConnectorType = 'repository' | 'output' | 'authority' | 'transformation'

interface ConnectorFormData {
  name: string
  description: string
  type: ConnectorType
  className: string
  maxConnections: number
  configuration: Record<string, string | boolean>
}

const getConnectorIconInfo = (className: string) => {
  if (className.includes('filesystem.FileConnector') || className.includes('FileSystem')) {
    return { icon: HardDrive, color: 'text-blue-400', bg: 'bg-blue-400/10', border: 'border-blue-500/20' }
  }
  if (className.includes('Alfresco') || className.includes('Aps') || className.includes('aps')) {
    return { icon: Server, color: 'text-amber-400', bg: 'bg-amber-400/10', border: 'border-amber-500/20' }
  }
  if (className.includes('Flowable') || className.includes('flowable')) {
    return { icon: Layers, color: 'text-blue-400', bg: 'bg-blue-400/10', border: 'border-blue-500/20' }
  }
  if (className.includes('Camunda') || className.includes('camunda')) {
    return { icon: Cpu, color: 'text-rose-400', bg: 'bg-rose-400/10', border: 'border-rose-500/20' }
  }
  if (className.includes('Cmis') || className.includes('cmis')) {
    return { icon: Layers, color: 'text-teal-400', bg: 'bg-teal-400/10', border: 'border-teal-500/20' }
  }
  if (className.includes('Iceberg')) {
    return { icon: Database, color: 'text-sky-400', bg: 'bg-sky-400/10', border: 'border-sky-500/20' }
  }
  if (className.includes('Jdbc') || className.includes('jdbc')) {
    return { icon: Database, color: 'text-amber-400', bg: 'bg-amber-400/10', border: 'border-amber-500/20' }
  }
  if (className.includes('VectorOutputConnector') || className.includes('vector')) {
    return { icon: Network, color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-500/20' }
  }
  if (className.includes('Milvus')) {
    return { icon: Cpu, color: 'text-cyan-400', bg: 'bg-cyan-400/10', border: 'border-cyan-500/20' }
  }
  if (className.includes('Qdrant')) {
    return { icon: Network, color: 'text-red-400', bg: 'bg-red-400/10', border: 'border-red-500/20' }
  }
  if (className.includes('Vespa')) {
    return { icon: Layers, color: 'text-violet-400', bg: 'bg-violet-400/10', border: 'border-violet-500/20' }
  }
  if (className.includes('elasticsearch')) {
    return { icon: Search, color: 'text-green-400', bg: 'bg-green-400/10', border: 'border-green-500/20' }
  }
  if (className.includes('opensearch') || className.includes('OpenSearch')) {
    return { icon: Search, color: 'text-orange-400', bg: 'bg-orange-400/10', border: 'border-orange-500/20' }
  }
  if (className.includes('solr')) {
    return { icon: Sun, color: 'text-yellow-400', bg: 'bg-yellow-400/10', border: 'border-yellow-500/20' }
  }
  if (className.includes('luxir') || className.includes('Luxir')) {
    return { icon: Sparkles, color: 'text-cyan-400', bg: 'bg-cyan-400/10', border: 'border-cyan-500/20' }
  }
  if (className.includes('seatunnel') || className.includes('SeaTunnel')) {
    return { icon: Network, color: 'text-blue-400', bg: 'bg-blue-400/10', border: 'border-blue-500/20' }
  }
  if (className.toLowerCase().includes('ozone')) {
    return { icon: Archive, color: 'text-amber-400', bg: 'bg-amber-400/10', border: 'border-amber-500/20' }
  }
  if (className.toLowerCase().includes('s3') && !className.toLowerCase().includes('opensearch')) {
    return { icon: Cloud, color: 'text-orange-400', bg: 'bg-orange-400/10', border: 'border-orange-500/20' }
  }
  if (className.includes('StormCrawler') || className.includes('stormcrawler')) {
    return { icon: Globe, color: 'text-sky-400', bg: 'bg-sky-400/10', border: 'border-sky-500/20' }
  }
  if (className.includes('GrpcPush') || className.includes('grpc-push') || className.includes('grpcPush')) {
    return { icon: Network, color: 'text-violet-400', bg: 'bg-violet-400/10', border: 'border-violet-500/20' }
  }
  if (className.includes('Ollama')) {
    return { icon: Cpu, color: 'text-purple-400', bg: 'bg-purple-400/10', border: 'border-purple-500/20' }
  }
  if (className.includes('OpenAI')) {
    return { icon: Sparkles, color: 'text-pink-400', bg: 'bg-pink-400/10', border: 'border-pink-500/20' }
  }
  if (className.includes('ActiveDirectory')) {
    return { icon: ShieldCheck, color: 'text-indigo-400', bg: 'bg-indigo-400/10', border: 'border-indigo-500/20' }
  }
  if (className.includes('LDAP')) {
    return { icon: Key, color: 'text-teal-400', bg: 'bg-teal-400/10', border: 'border-teal-500/20' }
  }
  if (className.includes('openarch') || className.includes('OpenArch')) {
    return { icon: Layers, color: 'text-indigo-400', bg: 'bg-indigo-400/10', border: 'border-indigo-500/20' }
  }
  return { icon: Plug2, color: 'text-muted-foreground', bg: 'bg-muted-foreground/10', border: 'border-muted-foreground/20' }
}

interface S3HeaderPair {
  key: string
  value: string
}

interface S3VendorPreset {
  id: string
  name: string
  description: string
  headers: S3HeaderPair[]
}

const S3_VENDOR_PRESETS: S3VendorPreset[] = [
  {
    id: 'ecs-full',
    name: 'Dell EMC ECS (Metadata Search & Multi-Tenancy)',
    description: 'Real-time metadata search indexing, tenant namespace routing, and retention',
    headers: [
      { key: 'x-emc-metadata-search', value: 'Department=${metadata.department},Classification=${metadata.classification}' },
      { key: 'x-emc-namespace', value: 'default-namespace' },
      { key: 'x-emc-retention-period', value: '31536000' }
    ]
  },
  {
    id: 'ecs-namespace',
    name: 'Dell EMC ECS (Tenant Namespace only)',
    description: 'Directs object ingestion to a dedicated ECS tenant namespace',
    headers: [
      { key: 'x-emc-namespace', value: 'finance-tenant' }
    ]
  },
  {
    id: 'ibm-cos',
    name: 'IBM Cloud Object Storage (Key Protect SSE)',
    description: 'Enforces IBM Key Protect customer-root-key envelope encryption',
    headers: [
      { key: 'ibm-sse-kp-customer-root-key-crn', value: 'crn:v1:bluemix:public:kms:us-south:a/account-id:key-id' },
      { key: 'ibm-sse-kp-encryption-algorithm', value: '256' }
    ]
  },
  {
    id: 'hitachi-hcp',
    name: 'Hitachi Content Platform (HCP Retention & Shredding)',
    description: 'Enforces HCP regulatory retention hold and cryptographic shredding',
    headers: [
      { key: 'x-hcp-retention', value: '0' },
      { key: 'x-hcp-shred', value: 'true' }
    ]
  },
  {
    id: 'netapp-sg',
    name: 'NetApp StorageGRID (Compliance Retention)',
    description: 'Applies NetApp StorageGRID regulatory compliance retention lock',
    headers: [
      { key: 'x-ntap-sg-compliance-retention', value: '2030-12-31T23:59:59Z' }
    ]
  },
  {
    id: 'cloudflare-r2',
    name: 'Cloudflare R2 (Jurisdiction Placement)',
    description: 'Directs data localization to European Union or specific jurisdiction',
    headers: [
      { key: 'cf-placement', value: 'eu' }
    ]
  },
  {
    id: 'standard-s3-meta',
    name: 'Standard S3 / MinIO (Custom User Metadata)',
    description: 'Attaches custom x-amz-meta-* user headers with document and metadata variables',
    headers: [
      { key: 'x-amz-meta-source-id', value: '${document.id}' },
      { key: 'x-amz-meta-department', value: '${metadata.department | Unknown}' }
    ]
  }
]

export default function ConnectorForm() {
  const [activeTab, setActiveTab] = useState<ConnectorType>('repository')
  const [connectors, setConnectors] = useState<ConnectorFormData[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [selectedConnector, setSelectedConnector] = useState<string | null>(null)

  const [s3CustomHeaders, setS3CustomHeaders] = useState<S3HeaderPair[]>([])
  const [selectedPreset, setSelectedPreset] = useState<string>('')
  const [newHeaderKey, setNewHeaderKey] = useState('')
  const [newHeaderValue, setNewHeaderValue] = useState('')

  const updateS3Headers = (headers: S3HeaderPair[]) => {
    setS3CustomHeaders(headers)
    const map: Record<string, string> = {}
    headers.forEach(h => {
      if (h.key && h.key.trim()) {
        map[h.key.trim()] = h.value || ''
      }
    })
    setValue('configuration.customHeaders', JSON.stringify(map))
  }

  const handleApplyPreset = (presetId: string) => {
    const preset = S3_VENDOR_PRESETS.find(p => p.id === presetId)
    if (!preset) return
    const existingMap = new Map<string, string>(s3CustomHeaders.map(h => [h.key, h.value]))
    preset.headers.forEach(h => existingMap.set(h.key, h.value))
    const merged = Array.from(existingMap.entries()).map(([key, value]) => ({ key, value }))
    updateS3Headers(merged)
    setSelectedPreset('')
  }

  const handleAddCustomHeader = () => {
    if (!newHeaderKey.trim()) return
    const existing = s3CustomHeaders.filter(h => h.key !== newHeaderKey.trim())
    const updated = [...existing, { key: newHeaderKey.trim(), value: newHeaderValue }]
    updateS3Headers(updated)
    setNewHeaderKey('')
    setNewHeaderValue('')
  }

  const handleRemoveCustomHeader = (keyToRemove: string) => {
    const updated = s3CustomHeaders.filter(h => h.key !== keyToRemove)
    updateS3Headers(updated)
  }

  const handleHeaderChange = (index: number, field: 'key' | 'value', val: string) => {
    const updated = [...s3CustomHeaders]
    updated[index] = { ...updated[index], [field]: val }
    updateS3Headers(updated)
  }

  const [isCheckingConnection, setIsCheckingConnection] = useState(false)
  const [checkResult, setCheckResult] = useState<{ success: boolean; message: string; details?: string } | null>(null)

  const { register, handleSubmit, formState: { errors }, reset, setValue, watch, getValues } = useForm<ConnectorFormData>({
    defaultValues: {
      maxConnections: 10,
      type: 'repository',
      configuration: {}
    },
    shouldUnregister: true
  })

  const selectedClass = watch('className')
  const watchMaxConnections = watch('maxConnections') || 10
  const vespaTlsEnabled = watch('configuration.vespaTlsEnabled')
  // Saved configuration values always come back as strings (the backend's configuration map is
  // Map<String,String>), so a plain truthy check would treat the string "false" as checked/true.
  const vespaTlsEnabledBool = vespaTlsEnabled === true || vespaTlsEnabled === 'true'
  const vespaEndpointValue = watch('configuration.vespaEndpoint')
  const solrMode = watch('configuration.solrMode') || 'standalone'
  const doxisAuthType = watch('configuration.authType') || 'basic'
  const sharePointCrawlMode = watch('configuration.crawlMode') || 'delta'
  const mfilesAuthType = watch('configuration.authType') || 'token'
  const mfilesCrawlMode = watch('configuration.crawlMode') || 'search'
  const filenetAuthType = watch('configuration.authType') || 'basic'
  const filenetCrawlMode = watch('configuration.crawlMode') || 'query'
  const openarchCrawlMode = watch('configuration.crawlMode') || 'QUERY'

  const fetchConnectors = async () => {
    setIsLoading(true)
    try {
      const response = await connectorApi.getAll(activeTab)
      setConnectors(response.data)
    } catch (error) {
      console.error('Error fetching connectors:', error)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    fetchConnectors()
    handleReset()
    setValue('type', activeTab)
  }, [activeTab])

  const handleReset = () => {
    setSelectedConnector(null)
    setCheckResult(null)
    setS3CustomHeaders([])
    setSelectedPreset('')
    setNewHeaderKey('')
    setNewHeaderValue('')
    reset({ 
      name: '', 
      description: '', 
      className: '', 
      maxConnections: 10, 
      type: activeTab,
      configuration: {} 
    })
  }

  const handleSelectConnector = (connector: ConnectorFormData) => {
    setSelectedConnector(connector.name)
    setCheckResult(null)
    setSelectedPreset('')
    setNewHeaderKey('')
    setNewHeaderValue('')
    reset(connector)

    if (connector.className === 'org.opencrawling.s3.S3OutputConnector' && connector.configuration) {
      if (connector.configuration.customHeaders) {
        try {
          const parsed = typeof connector.configuration.customHeaders === 'string'
            ? JSON.parse(connector.configuration.customHeaders as string)
            : connector.configuration.customHeaders
          const list = Object.entries(parsed).map(([key, value]) => ({ key, value: String(value) }))
          setS3CustomHeaders(list)
        } catch {
          setS3CustomHeaders([])
        }
      } else {
        const list: S3HeaderPair[] = []
        Object.entries(connector.configuration).forEach(([k, v]) => {
          if (k.startsWith('customHeader.')) {
            list.push({ key: k.substring('customHeader.'.length), value: String(v) })
          }
        })
        setS3CustomHeaders(list)
      }
    } else {
      setS3CustomHeaders([])
    }
  }

  const handleCheckConnection = async () => {
    setIsCheckingConnection(true)
    setCheckResult(null)
    try {
      const formData = getValues()
      const response = await connectorApi.checkConnection({ ...formData, type: activeTab })
      setCheckResult(response.data)
    } catch (error: any) {
      console.error('Error checking connection:', error)
      setCheckResult({
        success: false,
        message: error.response?.data?.message || error.message || 'Failed to communicate with OpenCrawling server.',
        details: error.toString()
      })
    } finally {
      setIsCheckingConnection(false)
    }
  }

  const onSubmit = async (data: ConnectorFormData) => {
    setIsSaving(true)
    try {
      await connectorApi.create({ ...data, type: activeTab })
      await fetchConnectors()
      handleReset()
    } catch (error) {
      console.error('Error saving connector:', error)
      alert('Failed to save connector')
    } finally {
      setIsSaving(false)
    }
  }

  const handleDelete = async (e: React.MouseEvent, name: string) => {
    e.stopPropagation()
    if (!confirm(`Are you sure you want to delete ${name}?`)) return
    try {
      await connectorApi.delete(name)
      if (selectedConnector === name) handleReset()
      await fetchConnectors()
    } catch (error) {
      console.error('Error deleting connector:', error)
    }
  }

  const connectorClasses = {
    repository: [
      { label: 'File System', value: 'org.opencrawling.filesystem.FileSystemRepositoryConnector' },
      { label: 'Alfresco Content Services Repository', value: 'org.opencrawling.alfresco.AlfrescoRepositoryConnector' },
      { label: 'Apache Iceberg Catalog Table', value: 'org.opencrawling.iceberg.IcebergRepositoryConnector' },
      { label: 'Flowable Repository Connector', value: 'org.opencrawling.flowable.FlowableRepositoryConnector' },
      { label: 'Camunda Repository Connector', value: 'org.opencrawling.camunda.CamundaRepositoryConnector' },
      { label: 'Alfresco Process Services (APS) Repository', value: 'org.opencrawling.aps.ApsRepositoryConnector' },
      { label: 'Apache StormCrawler Web Engine', value: 'org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector' },
      { label: 'OASIS CMIS Repository (1.0 / 1.1)', value: 'org.opencrawling.cmis.CmisRepositoryConnector' },
      { label: 'Doxis Repository (Intelligent Content Automation)', value: 'org.opencrawling.doxis.DoxisRepositoryConnector' },
      { label: 'Microsoft SharePoint (Microsoft Graph API)', value: 'org.opencrawling.sharepoint.SharePointRepositoryConnector' },
      { label: 'M-Files Repository (Knowledge Work Automation)', value: 'org.opencrawling.mfiles.MFilesRepositoryConnector' },
      { label: 'IBM FileNet P8 (Content Platform Engine)', value: 'org.opencrawling.filenet.FileNetRepositoryConnector' },
      { label: 'Relational Database (JDBC)', value: 'org.opencrawling.jdbc.JdbcRepositoryConnector' },
      { label: 'gRPC Push Ingestion Connector', value: 'org.opencrawling.push.grpc.GrpcPushRepositoryConnector' },
      { label: 'Hyland OpenArch Federated Repository', value: 'org.opencrawling.openarch.repository.OpenArchRepositoryConnector' },
    ],
    transformation: [
      { label: 'Ollama Embedding', value: 'org.opencrawling.embedding.OllamaEmbeddingConnector' },
      { label: 'OpenAI Embedding', value: 'org.opencrawling.embedding.OpenAIEmbeddingConnector' }
    ],
    output: [
      { label: 'PGVector Store', value: 'org.opencrawling.vector.VectorOutputConnector' },
      { label: 'Milvus Vector Store', value: 'org.opencrawling.milvus.MilvusOutputConnector' },
      { label: 'Qdrant Vector Store', value: 'org.opencrawling.qdrant.QdrantOutputConnector' },
      { label: 'OpenSearch 2.x Output Connector', value: 'org.opencrawling.opensearch2.OpenSearch2OutputConnector' },
      { label: 'OpenSearch 3.x Output Connector', value: 'org.opencrawling.opensearch3.OpenSearch3OutputConnector' },
      { label: 'Vespa Hybrid Search Store', value: 'org.opencrawling.vespa.VespaOutputConnector' },
      { label: 'Apache Solr 10 Output Connector', value: 'org.opencrawling.solr.SolrOutputConnector' },
      { label: 'Luxir Hybrid Search Store', value: 'org.opencrawling.luxir.LuxirOutputConnector' },
      { label: 'Apache SeaTunnel Distributed Fan-Out', value: 'org.opencrawling.seatunnel.SeaTunnelOutputConnector' },
      { label: 'Apache Ozone (Migration Mode only)', value: 'org.opencrawling.ozone.OzoneOutputConnector' },
      { label: 'AWS S3 (Migration Mode only)', value: 'org.opencrawling.s3.S3OutputConnector' },
      { label: 'Hyland OpenArch Federated Ingestion & Parquet Vector Bridge', value: 'org.opencrawling.openarch.output.OpenArchOutputConnector' },
    ],
    authority: [
      { label: 'Active Directory', value: 'org.opencrawling.authorities.authorities.activedirectory.ActiveDirectoryAuthority' },
      { label: 'LDAP', value: 'org.opencrawling.authorities.authorities.ldap.LDAPAuthority' },
    ]
  }

  return (
    <div className="space-y-6 animate-in fade-in duration-500 pb-20">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Connector Configuration</h1>
          <p className="text-muted text-sm">Create and manage connections to external systems.</p>
        </div>
        {selectedConnector && (
          <button 
            type="button"
            onClick={handleReset}
            className="btn-primary flex items-center gap-2 bg-gradient-to-r from-cyan-500 to-blue-500 text-black border border-cyan-400/25 shadow-lg shadow-cyan-500/20"
          >
            <Plus className="w-4 h-4 text-black" />
            New Connector
          </button>
        )}
      </div>

      <div className="flex gap-1 p-1 bg-slate-900 rounded-lg w-fit border border-border">
        {(['repository', 'transformation', 'output', 'authority'] as ConnectorType[]).map((tab) => (
          <button 
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`flex items-center gap-2 px-4 py-2 rounded-md text-sm font-medium transition-colors capitalize ${activeTab === tab ? 'bg-primary text-primary-foreground' : 'text-muted hover:text-foreground'}`}
          >
            {tab === 'repository' && <Plug2 className="w-4 h-4" />}
            {tab === 'transformation' && <Cpu className="w-4 h-4" />}
            {tab === 'output' && <Settings2 className="w-4 h-4" />}
            {tab === 'authority' && <ShieldCheck className="w-4 h-4" />}
            {tab}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* Existing Connectors List */}
        <div className="lg:col-span-1 space-y-4">
            <div className="flex justify-between items-center px-1">
               <h3 className="font-semibold text-sm uppercase tracking-wider text-muted">Existing {activeTab}s</h3>
               {selectedConnector && (
                 <button 
                   type="button"
                   onClick={handleReset}
                   className="flex items-center gap-1 text-xs text-primary hover:text-primary-foreground bg-primary/10 hover:bg-primary px-2.5 py-1 rounded transition-colors font-medium animate-in fade-in duration-200"
                   title="Add New Connector"
                 >
                   <Plus className="w-3.5 h-3.5" />
                   New
                 </button>
               )}
            </div>
           <div className="space-y-3">
              {isLoading ? (
                <div className="flex justify-center p-8"><Loader2 className="w-6 h-6 animate-spin text-primary" /></div>
              ) : connectors.length === 0 ? (
                <div className="p-4 border border-dashed border-border rounded-lg text-center text-sm text-muted italic">
                   No {activeTab} connectors found.
                </div>
              ) : (
                connectors.map((c) => {
                  const iconInfo = getConnectorIconInfo(c.className);
                  const IconComponent = iconInfo.icon;
                  return (
                    <div 
                      key={c.name} 
                      onClick={() => handleSelectConnector(c)}
                      className={`card-container !p-4 group cursor-pointer transition-all ${selectedConnector === c.name ? 'border-primary bg-primary/5 ring-1 ring-primary/20' : 'hover:border-primary/50'}`}
                    >
                       <div className="flex justify-between items-start">
                          <div className="flex items-center gap-3">
                             <div className={`p-2 rounded-lg border ${iconInfo.bg} ${iconInfo.color} ${iconInfo.border} flex items-center justify-center`}>
                                <IconComponent className="w-5 h-5" />
                             </div>
                             <div>
                                <h4 className={`font-bold transition-colors ${selectedConnector === c.name ? 'text-primary' : 'text-foreground'}`}>{c.name}</h4>
                                <p className="text-xs text-muted truncate max-w-[150px]">{c.className.split('.').pop()}</p>
                             </div>
                          </div>
                          <button 
                            onClick={(e) => handleDelete(e, c.name)}
                            className={`p-1 text-muted hover:text-destructive transition-all ${selectedConnector === c.name ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                       </div>
                    </div>
                  );
                })
              )}
           </div>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="lg:col-span-2 space-y-6">
          {Object.keys(errors).length > 0 && (
            <div className="p-4 bg-red-500/10 border border-red-500/20 text-red-400 text-sm rounded-lg flex items-center gap-2 animate-in fade-in duration-200">
              <AlertCircle className="w-5 h-5 text-red-500 flex-shrink-0" />
              <span>Please fill in all required fields, including any technical configurations.</span>
            </div>
          )}
          <div className="card-container space-y-4">
            <h3 className="text-lg font-semibold flex items-center gap-2 border-b border-border pb-4">
              {selectedConnector ? <Settings2 className="w-5 h-5 text-primary" /> : <Plus className="w-5 h-5 text-primary" />}
              {selectedConnector ? `Edit Connector: ${selectedConnector}` : `Add New ${activeTab}`}
            </h3>
            
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-sm font-medium">Connector Name</label>
                <input 
                  {...register('name', { required: true })}
                  readOnly={!!selectedConnector}
                  placeholder="e.g. My File System"
                  className={`w-full border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none transition-colors ${selectedConnector ? 'bg-slate-900 border-border text-muted cursor-not-allowed' : 'bg-background border-border'} ${errors.name ? 'border-destructive' : ''}`}
                />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">Connector Class</label>
                <select 
                  {...register('className', { required: true })}
                  className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                >
                  <option value="">Select a class...</option>
                  {connectorClasses[activeTab].map(cls => (
                    <option key={cls.value} value={cls.value}>{cls.label}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium">Description</label>
              <textarea 
                {...register('description')}
                rows={2}
                placeholder="Brief description..."
                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
              />
            </div>

            <div className="flex justify-between items-center pt-4 border-t border-border">
               <div className="flex-1 max-w-[200px] space-y-1">
                  <label className="text-xs text-muted uppercase font-bold">Max Connections</label>
                  <div className="flex items-center gap-3">
                    <input type="range" {...register('maxConnections')} min="1" max="100" className="flex-1 accent-primary" />
                    <span className="font-mono text-sm text-primary w-8 text-right">{watchMaxConnections}</span>
                  </div>
               </div>
               <div className="flex gap-3">
                  <button 
                    type="button" 
                    onClick={handleCheckConnection} 
                    disabled={isCheckingConnection || !selectedClass} 
                    className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium border border-cyan-500/30 bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400 transition-colors disabled:opacity-50"
                    title="Test connection consistency and reachability"
                  >
                    {isCheckingConnection ? <Loader2 className="w-4 h-4 animate-spin text-cyan-400" /> : <ShieldCheck className="w-4 h-4 text-cyan-400" />}
                    Check Connection
                  </button>
                  <button type="button" onClick={handleReset} className="btn-secondary">
                    {selectedConnector ? 'Cancel' : 'Reset'}
                  </button>
                  <button type="submit" disabled={isSaving} className="btn-primary flex items-center gap-2 min-w-[120px] justify-center">
                    {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    {selectedConnector ? 'Update' : 'Save'}
                  </button>
               </div>
            </div>

            {checkResult && (
              <div className={`p-4 rounded-lg border text-sm flex items-start gap-3 animate-in fade-in duration-300 ${checkResult.success ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400' : 'bg-red-500/10 border-red-500/30 text-red-400'}`}>
                {checkResult.success ? <ShieldCheck className="w-5 h-5 flex-shrink-0 text-emerald-400 mt-0.5" /> : <AlertCircle className="w-5 h-5 flex-shrink-0 text-red-400 mt-0.5" />}
                <div className="flex-1 space-y-1">
                  <p className="font-semibold">{checkResult.success ? 'Connection Verified & Reliable' : 'Connection Verification Failed'}</p>
                  <p className="text-xs opacity-90">{checkResult.message}</p>
                  {checkResult.details && (
                    <pre className="mt-2 p-2 bg-black/40 rounded text-[11px] font-mono max-h-24 overflow-y-auto whitespace-pre-wrap">{checkResult.details}</pre>
                  )}
                </div>
              </div>
            )}
          </div>

          <div className="card-container space-y-4">
             <div className="flex items-center justify-between border-b border-border pb-4">
                <h3 className="text-lg font-semibold flex items-center gap-2">
                  <Settings2 className="w-5 h-5 text-primary" />
                  Technical Configuration
                </h3>
             </div>

             {!selectedClass ? (
                <div className="p-4 bg-slate-900/50 border border-dashed border-border rounded-lg text-center text-sm text-muted">
                  Please select a Connector Class above to show configuration parameters.
                </div>
             ) : (
                <div className="space-y-4 animate-in fade-in duration-200">
                  {/* File System */}
                  {(selectedClass === 'org.opencrawling.filesystem.FileSystemRepositoryConnector' || selectedClass === 'org.opencrawling.crawler.connectors.filesystem.FileConnector') && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Root Path / Scanning Directory</label>
                        <input 
                          {...register('configuration.rootPath', { required: true })}
                          placeholder="e.g. /Users/documents/scan"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">The root folder on the local or mounted filesystem that this connector is authorized to scan.</p>
                      </div>

                      <div className="space-y-2 flex items-center gap-2 pt-2 col-span-2">
                        <input 
                          type="checkbox"
                          id="includeAclsFilesystem"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsFilesystem" className="text-sm font-medium cursor-pointer">
                          Extract POSIX ACLs, File Ownership & Permissions
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-blue-500/10 border border-blue-500/20 rounded-md text-xs text-blue-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-blue-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-blue-200">Zero-Trust POSIX & Collocated Pushdown: </span>
                          Extracts POSIX octal mode (<code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">file_mode_octal</code>), owner (<code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">file_owner</code>), and group (<code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">file_group</code>). Stamped OS identities (<code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">file_identity_users</code>, <code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">file_identity_groups</code>) enable zero-trust SQL bitmask pushdown and OIS envelope security.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Alfresco Content Services Repository */}
                  {selectedClass === 'org.opencrawling.alfresco.AlfrescoRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Alfresco API URL</label>
                        <input 
                          {...register('configuration.url', { required: true })}
                          placeholder="http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1"
                          defaultValue="http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username', { required: true })}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password', { required: true })}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select 
                          {...register('configuration.crawlMode')}
                          defaultValue="folder"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="folder">Folder Tree Traversal</option>
                          <option value="query">Search API Query (AFTS / CMIS SQL / Lucene)</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Query Language (For Query Mode)</label>
                        <select 
                          {...register('configuration.queryLanguage')}
                          defaultValue="afts"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="afts">AFTS (Alfresco Full Text Search)</option>
                          <option value="cmis">CMIS SQL</option>
                          <option value="lucene">Lucene</option>
                        </select>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Search Query (For Query Mode)</label>
                        <input 
                          {...register('configuration.searchQuery')}
                          placeholder="TYPE:'cm:content' AND PATH:'/app:company_home/st:sites/cm:finance//*'"
                          defaultValue="TYPE:'cm:content'"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Folder Path</label>
                        <input 
                          {...register('configuration.rootFolderPath')}
                          placeholder="/Company Home"
                          defaultValue="/"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Folder / Node ID (Optional)</label>
                        <input 
                          {...register('configuration.rootFolderId')}
                          placeholder="e.g. -root- or specific UUID"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Site ID (Optional Scope)</label>
                        <input 
                          {...register('configuration.siteId')}
                          placeholder="e.g. finance or marketing"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Excluded Folders (Comma-separated)</label>
                        <input 
                          {...register('configuration.excludedFolders')}
                          placeholder="Data Dictionary, /Sites/trash"
                          defaultValue="Data Dictionary"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize', { valueAsNumber: true })}
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max Content Size (Bytes)</label>
                        <input 
                          type="number"
                          {...register('configuration.maxContentSizeBytes', { valueAsNumber: true })}
                          defaultValue={52428800}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">MIME Type Filter (Optional, comma-separated)</label>
                        <input 
                          {...register('configuration.mimeTypeFilter')}
                          placeholder="e.g. application/pdf, text/plain, image/*"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeSubfoldersAlfresco"
                          {...register('configuration.includeSubfolders')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSubfoldersAlfresco" className="text-sm font-medium cursor-pointer">
                          Include Subfolders Recursively
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeContentStreamAlfresco"
                          {...register('configuration.includeContentStream')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeContentStreamAlfresco" className="text-sm font-medium cursor-pointer">
                          Fetch Binary Content Stream
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsAlfresco"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsAlfresco" className="text-sm font-medium cursor-pointer">
                          Include Access Control Lists (ACLs) & Collocated Security
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-amber-500/10 border border-amber-500/20 rounded-md text-xs text-amber-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-amber-200">Collocated Database Pattern: </span>
                          Stamps <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">alfresco_node_id</code>, <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">alfresco_identity_users</code>, and <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">alfresco_identity_groups</code>. Enables zero-lag SQL pushdown on <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">alf_node</code> and zero-trust ACL validation.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Flowable Repository Connector */}
                  {selectedClass === 'org.opencrawling.flowable.FlowableRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Flowable REST API URL</label>
                        <input 
                          {...register('configuration.url', { required: true })}
                          placeholder="http://localhost:8080/flowable-rest/service"
                          defaultValue="http://localhost:8080/flowable-rest/service"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username', { required: true })}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password', { required: true })}
                          placeholder="test"
                          defaultValue="test"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize')}
                          defaultValue="100"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Process Definition Key (Filter)</label>
                        <input 
                          {...register('configuration.processDefinitionKey')}
                          placeholder="e.g. invoice-process"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Scope</label>
                        <select 
                          {...register('configuration.scope')}
                          defaultValue="all"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="all">All (Active & Completed)</option>
                          <option value="completed">Completed Only</option>
                          <option value="active">Active Only</option>
                        </select>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeVariables"
                          {...register('configuration.includeVariables')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeVariables" className="text-sm font-medium cursor-pointer">
                          Include Historic BPMN Variables
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsFlowable"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsFlowable" className="text-sm font-medium cursor-pointer">
                          Include Identity Links & Zero-Trust ACLs
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-blue-500/10 border border-blue-500/20 rounded-md text-xs text-blue-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-blue-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-blue-200">Collocated Workflow Pushdown: </span>
                          Stamps <code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">flowable_process_instance_id</code>, <code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">flowable_identity_users</code>, and <code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">flowable_identity_groups</code>. Enables zero-lag joins against <code className="bg-blue-900/40 px-1 py-0.5 rounded text-blue-200">ACT_HI_IDENTITYLINK</code>.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Camunda Repository Connector */}
                  {selectedClass === 'org.opencrawling.camunda.CamundaRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Camunda REST API Base URL</label>
                        <input 
                          {...register('configuration.url', { required: true })}
                          placeholder="http://localhost:8080/engine-rest"
                          defaultValue="http://localhost:8080/engine-rest"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username')}
                          placeholder="demo"
                          defaultValue="demo"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password')}
                          placeholder="demo"
                          defaultValue="demo"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize', { valueAsNumber: true })}
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Process Definition Key (Optional)</label>
                        <input 
                          {...register('configuration.processDefinitionKey')}
                          placeholder="e.g. invoice-process"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Workflow Instance Scope</label>
                        <select 
                          {...register('configuration.scope')}
                          defaultValue="all"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="all">All (Active & Completed)</option>
                          <option value="completed">Completed Only</option>
                          <option value="active">Active Only</option>
                        </select>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeVariablesCamunda"
                          {...register('configuration.includeVariables')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeVariablesCamunda" className="text-sm font-medium cursor-pointer">
                          Include Historic BPMN Variables
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsCamunda"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsCamunda" className="text-sm font-medium cursor-pointer">
                          Include Identity Links & Zero-Trust ACLs
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-rose-500/10 border border-rose-500/20 rounded-md text-xs text-rose-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-rose-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-rose-200">Collocated Workflow Pushdown: </span>
                          Stamps <code className="bg-rose-900/40 px-1 py-0.5 rounded text-rose-200">camunda_process_instance_id</code>, <code className="bg-rose-900/40 px-1 py-0.5 rounded text-rose-200">camunda_identity_users</code>, and <code className="bg-rose-900/40 px-1 py-0.5 rounded text-rose-200">camunda_identity_groups</code>. Enables zero-lag joins against <code className="bg-rose-900/40 px-1 py-0.5 rounded text-rose-200">ACT_HI_IDENTITYLINK</code>.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Alfresco Process Services (APS) Repository Connector */}
                  {selectedClass === 'org.opencrawling.aps.ApsRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">APS Enterprise REST API URL</label>
                        <input 
                          {...register('configuration.url', { required: true })}
                          placeholder="http://localhost:8080/activiti-app/api/enterprise"
                          defaultValue="http://localhost:8080/activiti-app/api/enterprise"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username')}
                          placeholder="admin@app.activiti.com"
                          defaultValue="admin@app.activiti.com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password')}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize', { valueAsNumber: true })}
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Process Definition Key (Optional)</label>
                        <input 
                          {...register('configuration.processDefinitionKey')}
                          placeholder="e.g. loanApplication"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Tenant ID (Optional)</label>
                        <input 
                          {...register('configuration.tenantId')}
                          placeholder="e.g. enterprise-tenant-1"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Workflow Instance Scope</label>
                        <select 
                          {...register('configuration.scope')}
                          defaultValue="all"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="all">All (Active & Completed)</option>
                          <option value="completed">Completed Only</option>
                          <option value="active">Active Only</option>
                        </select>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeVariablesAps"
                          {...register('configuration.includeVariables')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeVariablesAps" className="text-sm font-medium cursor-pointer">
                          Include Process Variables
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeTasksAps"
                          {...register('configuration.includeTasks')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeTasksAps" className="text-sm font-medium cursor-pointer">
                          Include Tasks
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAttachmentsAps"
                          {...register('configuration.includeAttachments')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAttachmentsAps" className="text-sm font-medium cursor-pointer">
                          Include Workflow Attachments & Documents
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsAps"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsAps" className="text-sm font-medium cursor-pointer">
                          Include Identity Links & Zero-Trust ACLs
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="mcpEnabledAps"
                          {...register('configuration.mcpEnabled')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="mcpEnabledAps" className="text-sm font-medium cursor-pointer">
                          Enable APS MCP Server Discovery
                        </label>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">APS MCP Server Endpoint (Optional)</label>
                        <input 
                          {...register('configuration.mcpUrl')}
                          placeholder="http://localhost:8080/activiti-app/mcp"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>

                      <div className="col-span-2 p-3 bg-amber-500/10 border border-amber-500/20 rounded-md text-xs text-amber-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-amber-200">Collocated Workflow Pushdown: </span>
                          Stamps <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">aps_process_instance_id</code>, <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">aps_identity_users</code>, and <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">aps_identity_groups</code> for enterprise process authorization pushdown.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Apache StormCrawler Repository Connector */}
                  {selectedClass === 'org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Apache Storm Nimbus UI / REST API URL</label>
                        <input 
                          {...register('configuration.nimbusRestUrl', { required: true })}
                          placeholder="http://localhost:8080"
                          defaultValue="http://localhost:8080"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Nimbus Host</label>
                        <input 
                          {...register('configuration.nimbusHost', { required: true })}
                          placeholder="localhost"
                          defaultValue="localhost"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Nimbus Thrift Port</label>
                        <input 
                          type="number"
                          {...register('configuration.nimbusPort', { required: true })}
                          placeholder="6627"
                          defaultValue="6627"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">StormCrawler Topology Name</label>
                        <input 
                          {...register('configuration.topologyName', { required: true })}
                          placeholder="opencrawling-web-crawler"
                          defaultValue="opencrawling-web-crawler"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Politeness Crawl Delay (ms)</label>
                        <input 
                          type="number"
                          {...register('configuration.delayMs')}
                          placeholder="1000"
                          defaultValue="1000"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Seed URLs (Comma-separated)</label>
                        <input 
                          {...register('configuration.seeds', { required: true })}
                          placeholder="https://docs.example.com, https://developer.example.com"
                          defaultValue="https://docs.example.com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Custom User Agent</label>
                        <input 
                          {...register('configuration.customUserAgent')}
                          placeholder="OpenCrawling-StormCrawler-Bot/1.0"
                          defaultValue="OpenCrawling-StormCrawler-Bot/1.0"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>

                      <div className="col-span-2 p-3 bg-sky-500/10 border border-sky-500/20 rounded-md text-xs text-sky-300 flex items-start gap-2">
                        <Globe className="w-4 h-4 text-sky-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-sky-200">Web Lineage & Domain Governance: </span>
                          Automatically stamps <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">web_domain</code>, <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">web_scheme</code>, <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">canonical.url</code>, and <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">stormcrawler_topology</code> for domain boundary isolation and URL pushdown.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* OASIS CMIS 1.1 Repository Connector */}
                  {selectedClass === 'org.opencrawling.cmis.CmisRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">CMIS Endpoint URL</label>
                        <input 
                          {...register('configuration.endpointUrl', { required: true })}
                          placeholder="http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"
                          defaultValue="http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Binding Type</label>
                        <select 
                          {...register('configuration.bindingType')}
                          defaultValue="browser"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="browser">Browser Binding (JSON, CMIS 1.1)</option>
                          <option value="atompub">AtomPub Binding (XML, CMIS 1.0/1.1)</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Repository ID (Optional)</label>
                        <input 
                          {...register('configuration.repositoryId')}
                          placeholder="Leave blank for auto-discovery"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username')}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password')}
                          placeholder="admin"
                          defaultValue="admin"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select 
                          {...register('configuration.crawlMode')}
                          defaultValue="folder"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="folder">Folder Tree Traversal</option>
                          <option value="query">CMIS SQL Query</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Versions Mode</label>
                        <select 
                          {...register('configuration.versionsMode')}
                          defaultValue="latest_major"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="latest_major">Latest Major Version Only</option>
                          <option value="latest">Latest Version (including minor)</option>
                          <option value="all">All Versions</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Folder Path</label>
                        <input 
                          {...register('configuration.rootFolderPath')}
                          placeholder="/"
                          defaultValue="/"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Folder ID (Optional)</label>
                        <input 
                          {...register('configuration.rootFolderId')}
                          placeholder="e.g. workspace://SpacesStore/..."
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">CMIS SQL Query (For Query Mode)</label>
                        <input 
                          {...register('configuration.cmisQuery')}
                          placeholder="SELECT * FROM cmis:document"
                          defaultValue="SELECT * FROM cmis:document"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Excluded Folder Paths (Comma-separated)</label>
                        <input 
                          {...register('configuration.excludedFolderPaths')}
                          placeholder="/Sites/trash,/System"
                          defaultValue="/Sites/trash,/System"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize', { valueAsNumber: true })}
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max Content Size (Bytes)</label>
                        <input 
                          type="number"
                          {...register('configuration.maxContentSizeBytes', { valueAsNumber: true })}
                          defaultValue={52428800}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Initial Change Log Token (Optional)</label>
                        <input 
                          {...register('configuration.changeLogToken')}
                          placeholder="token string"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeSubfoldersCmis"
                          {...register('configuration.includeSubfolders')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSubfoldersCmis" className="text-sm font-medium cursor-pointer">
                          Include Subfolders Recursively
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeContentStreamCmis"
                          {...register('configuration.includeContentStream')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeContentStreamCmis" className="text-sm font-medium cursor-pointer">
                          Fetch Binary Content Stream
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsCmis"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsCmis" className="text-sm font-medium cursor-pointer">
                          Include Access Control Lists (ACLs) & Zero-Trust Security
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeSecondaryTypesCmis"
                          {...register('configuration.includeSecondaryTypes')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSecondaryTypesCmis" className="text-sm font-medium cursor-pointer">
                          Include Secondary Object Types / Aspects
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="changeLogEnabledCmis"
                          {...register('configuration.changeLogEnabled')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="changeLogEnabledCmis" className="text-sm font-medium cursor-pointer">
                          Enable Change Log Delta Processing
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-teal-500/10 border border-teal-500/20 rounded-md text-xs text-teal-300 flex items-start gap-2">
                        <ShieldCheck className="w-4 h-4 text-teal-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-teal-200">Zero-Trust ACL & Collocated Pushdown: </span>
                          Stamps <code className="bg-teal-900/40 px-1 py-0.5 rounded text-teal-200">cmis_object_id</code>, <code className="bg-teal-900/40 px-1 py-0.5 rounded text-teal-200">cmis_repository_id</code>, <code className="bg-teal-900/40 px-1 py-0.5 rounded text-teal-200">cmis_identity_users</code>, and <code className="bg-teal-900/40 px-1 py-0.5 rounded text-teal-200">cmis_identity_groups</code>. Pre-populates security permissions for collocated CMIS/Alfresco repository filtering and OIS envelopes.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Doxis 4 CSB Repository Connector */}
                  {selectedClass === 'org.opencrawling.doxis.DoxisRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div role="note" className="col-span-2 flex items-start gap-3 rounded-md border border-amber-500/20 bg-amber-400/10 px-3 py-2 text-sm">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
                        <p>
                          <span className="font-medium">Requires a valid, licensed Doxis 4 installation (SER Group).</span>{' '}
                          OpenCrawling does not include Doxis or any Doxis licence. Your licence must allow API (technical-user)
                          sessions, and you need a reachable CSB 14.4+ with its REST API and a technical user with read rights.
                          See the connector&apos;s README, section Prerequisites.
                        </p>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Doxis CSB REST API URL</label>
                        <input 
                          {...register('configuration.url', { required: true })}
                          placeholder="http://localhost:8080/restws/publicws/rest/api/v1"
                          defaultValue="http://localhost:8080/restws/publicws/rest/api/v1"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Authentication Type</label>
                        <select 
                          {...register('configuration.authType')}
                          defaultValue="basic"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="basic">Basic (CSB user name + password login)</option>
                          <option value="ticket">CSB Session Ticket</option>
                          <option value="oauth2">OAuth2 / OIDC Access Token</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Customer (Tenant)</label>
                        <input 
                          {...register('configuration.customerName')}
                          placeholder="e.g. DX4"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input 
                          {...register('configuration.username')}
                          placeholder="Supervisor"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input 
                          type="password"
                          {...register('configuration.password')}
                          placeholder=""
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      {doxisAuthType === 'ticket' && (
                        <>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Session Ticket</label>
                          <input
                            type="password"
                            {...register('configuration.sessionTicket')}
                            placeholder="CSB session ticket"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        </>
                      )}
                      {doxisAuthType === 'oauth2' && (
                        <>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Token URL</label>
                          <input
                            {...register('configuration.oauth2TokenUrl')}
                            placeholder="https://auth.example.com/oauth/token"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Client ID</label>
                          <input
                            {...register('configuration.oauth2ClientId')}
                            placeholder="opencrawling-client"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Client Secret</label>
                          <input
                            type="password"
                            {...register('configuration.oauth2ClientSecret')}
                            placeholder="client secret"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Scope (Optional)</label>
                          <input
                            {...register('configuration.oauth2Scope')}
                            placeholder="doxis.read"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Access Token (Optional, Instead of Client Credentials)</label>
                          <input
                            type="password"
                            {...register('configuration.oauth2AccessToken')}
                            placeholder="eyJ…"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        </>
                      )}
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Role</label>
                        <input 
                          {...register('configuration.role')}
                          placeholder="admins"
                          defaultValue="admins"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Repository / Archive ID</label>
                        <input 
                          {...register('configuration.repositoryId')}
                          placeholder="e.g. DB1 or the repository UUID"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select 
                          {...register('configuration.crawlMode')}
                          defaultValue="search"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="search">Document Class & Search Query (CQL)</option>
                          <option value="folder">Electronic File / Folder Traversal</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Version Mode</label>
                        <select 
                          {...register('configuration.versionMode')}
                          defaultValue="latest_only"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="latest_only">Latest Version Only</option>
                          <option value="all_versions">All Versions</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Document Classes (Comma-separated)</label>
                        <input 
                          {...register('configuration.documentClasses')}
                          placeholder="TX_MigratedDocument,TX_LargeExternalDocument"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root E-File ID (Folder Mode)</label>
                        <input 
                          {...register('configuration.rootFolderId')}
                          placeholder="e-file (record) UUID"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Search Query / Descriptors Filter (CQL WHERE)</label>
                        <input 
                          {...register('configuration.searchQuery')}
                          placeholder="OBJECTNUMBER2 LIKE 'contracts-2026*'"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Descriptor Metadata Prefix (Optional)</label>
                        <input 
                          {...register('configuration.descriptorPrefix')}
                          placeholder="empty = Doxis names as-is, or doxis_desc_"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Fallback Readers (No Instance ACEs)</label>
                        <input 
                          {...register('configuration.fallbackPrincipals')}
                          placeholder="e.g. group:Legal, everybody"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize', { valueAsNumber: true })}
                          placeholder="100"
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Parallel Documents</label>
                        <input 
                          type="number"
                          {...register('configuration.parallelism', { valueAsNumber: true })}
                          placeholder="2"
                          defaultValue={2}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max Content Size (Bytes)</label>
                        <input 
                          type="number"
                          {...register('configuration.maxContentSizeBytes', { valueAsNumber: true })}
                          placeholder="52428800"
                          defaultValue={52428800}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Timeout (Seconds)</label>
                        <input 
                          type="number"
                          {...register('configuration.timeoutSeconds', { valueAsNumber: true })}
                          placeholder="120"
                          defaultValue={120}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeSubfoldersDoxis"
                          {...register('configuration.includeSubfolders')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSubfoldersDoxis" className="text-sm font-medium cursor-pointer">
                          Include Child Folders & Sub-E-Files
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeContentStreamDoxis"
                          {...register('configuration.includeContentStream')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeContentStreamDoxis" className="text-sm font-medium cursor-pointer">
                          Include Content Streams
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeDescriptorsDoxis"
                          {...register('configuration.includeDescriptors')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeDescriptorsDoxis" className="text-sm font-medium cursor-pointer">
                          Include Descriptors
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="includeAclsDoxis"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsDoxis" className="text-sm font-medium cursor-pointer">
                          Include Security & ACLs
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="emitLogicalDeletesDoxis"
                          {...register('configuration.emitLogicalDeletes')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="emitLogicalDeletesDoxis" className="text-sm font-medium cursor-pointer">
                          Send Deletes for Logically Removed Documents
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="incrementalDoxis"
                          {...register('configuration.incremental')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="incrementalDoxis" className="text-sm font-medium cursor-pointer">
                          Incremental Crawl (Skip Unchanged, Detect Deletes; Search Mode)
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input 
                          type="checkbox"
                          id="tenantIsolationDoxis"
                          {...register('configuration.tenantIsolation')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="tenantIsolationDoxis" className="text-sm font-medium cursor-pointer">
                          Tenant Isolation (Qualify Readers with the Customer, e.g. DX4/Legal)
                        </label>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Incremental State Directory</label>
                        <input 
                          {...register('configuration.stateDirectory')}
                          placeholder="data/doxis-state"
                          defaultValue="data/doxis-state"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* Microsoft SharePoint (Microsoft Graph API) */}
                  {selectedClass === 'org.opencrawling.sharepoint.SharePointRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div role="note" className="col-span-2 flex items-start gap-3 rounded-md border border-amber-500/20 bg-amber-400/10 px-3 py-2 text-sm">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
                        <p>
                          <span className="font-medium">Requires a Microsoft Entra ID app registration.</span>{' '}
                          Grant it the Microsoft Graph application permission Sites.Read.All (or Sites.Selected with read access
                          to this site), have an administrator consent, and create a client secret. See the connector&apos;s
                          README, section Prerequisites.
                        </p>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">SharePoint Site URL</label>
                        <input
                          {...register('configuration.siteUrl', { required: true })}
                          placeholder="https://contoso.sharepoint.com/sites/engineering"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Entra ID Tenant ID</label>
                        <input
                          {...register('configuration.tenantId', { required: true })}
                          placeholder="72f988bf-86f1-41af-91ab-2d7cd011db47"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Application (Client) ID</label>
                        <input
                          {...register('configuration.clientId', { required: true })}
                          placeholder="00000000-0000-0000-0000-000000000000"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Client Secret</label>
                        <input
                          type="password"
                          {...register('configuration.clientSecret')}
                          placeholder=""
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Document Libraries (Comma-Separated)</label>
                        <input
                          {...register('configuration.driveNames')}
                          placeholder="empty = every document library, e.g. Documents,Contracts"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select
                          {...register('configuration.crawlMode')}
                          defaultValue="delta"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="delta">Delta Query (Incremental)</option>
                          <option value="tree">Folder Tree Traversal (Permission Reconciliation)</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">User Identities</label>
                        <select
                          {...register('configuration.userIdentity')}
                          defaultValue="email"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="email">E-Mail / User Principal Name</option>
                          <option value="id">Entra Object ID</option>
                        </select>
                      </div>
                      {sharePointCrawlMode === 'tree' && (
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Root Folder Path</label>
                          <input
                            {...register('configuration.rootFolderPath')}
                            placeholder="/ or /Specs/2026"
                            defaultValue="/"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      )}
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Fallback Readers (When No Permissions Are Readable)</label>
                        <input
                          {...register('configuration.fallbackPrincipals')}
                          placeholder="empty = nobody, or group:records,user:auditor@contoso.com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="col-span-2 pt-2">
                        <p className="text-sm font-medium">Broad Access Grants</p>
                        <p className="text-xs text-muted-foreground">
                          Leave empty to grant nothing through them. Enter an identity your callers carry, or public to make
                          such files readable by every caller.
                        </p>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">“Everyone Except External Users” Identity</label>
                        <input
                          {...register('configuration.everyoneExceptExternalIdentity')}
                          placeholder="e.g. contoso-employees"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">“Everyone” Identity</label>
                        <input
                          {...register('configuration.everyoneIdentity')}
                          placeholder="e.g. contoso-everyone"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Organization Sharing Links Identity</label>
                        <input
                          {...register('configuration.organizationLinkIdentity')}
                          placeholder="e.g. contoso-employees"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Anonymous Sharing Links Identity</label>
                        <input
                          {...register('configuration.anonymousLinkIdentity')}
                          placeholder="empty recommended"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Page Size</label>
                        <input
                          type="number"
                          {...register('configuration.batchSize')}
                          placeholder="100"
                          defaultValue={100}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Parallelism</label>
                        <input
                          type="number"
                          {...register('configuration.parallelism')}
                          placeholder="4"
                          defaultValue={4}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max File Size (Bytes)</label>
                        <input
                          type="number"
                          {...register('configuration.maxFileSizeBytes')}
                          placeholder="52428800"
                          defaultValue={52428800}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Timeout (Seconds)</label>
                        <input
                          type="number"
                          {...register('configuration.timeoutSeconds')}
                          placeholder="30"
                          defaultValue={30}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeContentStreamSharePoint"
                          {...register('configuration.includeContentStream')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeContentStreamSharePoint" className="text-sm font-medium cursor-pointer">
                          Include Content Streams
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeCustomFieldsSharePoint"
                          {...register('configuration.includeCustomFields')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeCustomFieldsSharePoint" className="text-sm font-medium cursor-pointer">
                          Include Custom List Item Fields
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeAclsSharePoint"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsSharePoint" className="text-sm font-medium cursor-pointer">
                          Include Entra ID Permissions & ACLs
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeSubfoldersSharePoint"
                          {...register('configuration.includeSubfolders')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSubfoldersSharePoint" className="text-sm font-medium cursor-pointer">
                          Include Subfolders (Tree Mode)
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="incrementalSharePoint"
                          {...register('configuration.incremental')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="incrementalSharePoint" className="text-sm font-medium cursor-pointer">
                          Incremental (Keep Delta Link and Item State)
                        </label>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">State Directory</label>
                        <input
                          {...register('configuration.stateDirectory')}
                          placeholder="data/sharepoint-state"
                          defaultValue="data/sharepoint-state"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Microsoft Graph URL (Sovereign Clouds)</label>
                        <input
                          {...register('configuration.graphUrl')}
                          placeholder="https://graph.microsoft.com/v1.0"
                          defaultValue="https://graph.microsoft.com/v1.0"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Entra ID Authority URL (Sovereign Clouds)</label>
                        <input
                          {...register('configuration.authorityUrl')}
                          placeholder="https://login.microsoftonline.com"
                          defaultValue="https://login.microsoftonline.com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* M-Files Vault Repository Connector */}
                  {selectedClass === 'org.opencrawling.mfiles.MFilesRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div role="note" className="col-span-2 flex items-start gap-3 rounded-md border border-amber-500/20 bg-amber-400/10 px-3 py-2 text-sm">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
                        <p>
                          <span className="font-medium">Requires a licensed M-Files Server (M-Files Corporation).</span>{' '}
                          OpenCrawling does not include M-Files or any M-Files licence. The M-Files Web Service (REST API) must be
                          enabled, and the crawl account needs read access to the vault. See the connector&apos;s README, section
                          Prerequisites.
                        </p>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">M-Files Web Service URL</label>
                        <input
                          {...register('configuration.url', { required: true })}
                          placeholder="https://localhost/REST"
                          defaultValue="https://localhost/REST"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Vault GUID</label>
                        <input
                          {...register('configuration.vaultGuid', { required: true })}
                          placeholder="{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Authentication Type</label>
                        <select
                          {...register('configuration.authType')}
                          defaultValue="token"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="token">Authentication Token (Username / Password)</option>
                          <option value="basic">Credentials on Every Request (X-Username / X-Password)</option>
                          <option value="oauth2">OAuth2 / Entra ID Bearer Token</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Windows Domain (Optional)</label>
                        <input
                          {...register('configuration.domain')}
                          placeholder="e.g. CORP"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      {mfilesAuthType !== 'oauth2' && (
                        <>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input
                            {...register('configuration.username')}
                            placeholder="mfiles_service_user"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input
                            type="password"
                            {...register('configuration.password')}
                            placeholder=""
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        </>
                      )}
                      {mfilesAuthType === 'oauth2' && (
                        <>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Token URL</label>
                          <input
                            {...register('configuration.oauth2TokenUrl')}
                            placeholder="https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Client ID</label>
                          <input
                            {...register('configuration.oauth2ClientId')}
                            placeholder="opencrawling-client-id"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Client Secret</label>
                          <input
                            type="password"
                            {...register('configuration.oauth2ClientSecret')}
                            placeholder="client secret"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OAuth2 Scope (Optional)</label>
                          <input
                            {...register('configuration.oauth2Scope')}
                            placeholder="api://m-files/.default"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Access Token (Optional, Instead of Client Credentials)</label>
                          <input
                            type="password"
                            {...register('configuration.oauth2AccessToken')}
                            placeholder="eyJ…"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        </>
                      )}
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select
                          {...register('configuration.crawlMode')}
                          defaultValue="search"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="search">Metadata & Class Search</option>
                          <option value="view">Predefined View</option>
                          <option value="all_objects">All Vault Objects</option>
                          <option value="vaf">Vault Application (VAF) Change Feed</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Object Type ID</label>
                        <input
                          {...register('configuration.objectType')}
                          placeholder="0 = Document, -1 = all types"
                          defaultValue={0}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Class IDs Filter (Comma-Separated)</label>
                        <input
                          {...register('configuration.classIds')}
                          placeholder="e.g. 14,22,5 (empty = all classes)"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      {mfilesCrawlMode === 'search' && (
                        <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Search Conditions (MFWS)</label>
                        <input
                          {...register('configuration.searchQuery')}
                          placeholder="e.g. p1020=5 or q=contract"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      )}
                      {mfilesCrawlMode === 'view' && (
                        <div className="space-y-2">
                        <label className="text-sm font-medium">View ID or Path</label>
                        <input
                          {...register('configuration.viewId')}
                          placeholder="e.g. 101 or v101/L5"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      )}
                      {mfilesCrawlMode === 'vaf' && (
                        <div className="space-y-2">
                        <label className="text-sm font-medium">VAF Base Path</label>
                        <input
                          {...register('configuration.vafBasePath')}
                          placeholder="/vaf/<app>"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      )}
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Version Mode</label>
                        <select
                          {...register('configuration.versionMode')}
                          defaultValue="latest_only"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="latest_only">Latest Version Only</option>
                          <option value="all_versions">All Versions</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Permission Identities</label>
                        <select
                          {...register('configuration.principalFormat')}
                          defaultValue="token"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="token">Tokens (mfiles:user:&lt;id&gt;, mfiles:group:&lt;id&gt;)</option>
                          <option value="name">User and Group Names</option>
                        </select>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Fallback Readers (When No Permissions Are Readable)</label>
                        <input
                          {...register('configuration.fallbackPrincipals')}
                          placeholder="empty = nobody, or group:records,user:auditor"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Modified After (Optional)</label>
                        <input
                          {...register('configuration.modifiedAfter')}
                          placeholder="2026-01-01T00:00:00Z"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size (Results per Search Request)</label>
                        <input
                          type="number"
                          {...register('configuration.batchSize')}
                          placeholder="500"
                          defaultValue={500}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Parallelism</label>
                        <input
                          type="number"
                          {...register('configuration.parallelism')}
                          placeholder="4"
                          defaultValue={4}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max File Size (Bytes)</label>
                        <input
                          type="number"
                          {...register('configuration.maxFileSizeBytes')}
                          placeholder="52428800"
                          defaultValue={52428800}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Timeout (Seconds)</label>
                        <input
                          type="number"
                          {...register('configuration.timeoutSeconds')}
                          placeholder="30"
                          defaultValue={30}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeFilesMFiles"
                          {...register('configuration.includeFiles')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeFilesMFiles" className="text-sm font-medium cursor-pointer">
                          Include Files & Attachments
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includePropertiesMFiles"
                          {...register('configuration.includeProperties')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includePropertiesMFiles" className="text-sm font-medium cursor-pointer">
                          Include Metadata Properties
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeAclsMFiles"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsMFiles" className="text-sm font-medium cursor-pointer">
                          Include Permissions & ACLs
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="emitDeletesMFiles"
                          {...register('configuration.emitDeletes')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="emitDeletesMFiles" className="text-sm font-medium cursor-pointer">
                          Send Tombstones for Deleted Objects
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="incrementalEnabledMFiles"
                          {...register('configuration.incrementalEnabled')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="incrementalEnabledMFiles" className="text-sm font-medium cursor-pointer">
                          Incremental Crawl (Watermark, Skip Unchanged)
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="qualifyPrincipalsMFiles"
                          {...register('configuration.qualifyPrincipals')}
                          defaultChecked={false}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="qualifyPrincipalsMFiles" className="text-sm font-medium cursor-pointer">
                          Qualify Identities with the Vault GUID
                        </label>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Incremental State Directory</label>
                        <input
                          {...register('configuration.stateDirectory')}
                          placeholder="data/mfiles-state"
                          defaultValue="data/mfiles-state"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* IBM FileNet P8 Repository Connector */}
                  {selectedClass === 'org.opencrawling.filenet.FileNetRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div role="note" className="col-span-2 flex items-start gap-3 rounded-md border border-amber-500/20 bg-amber-400/10 px-3 py-2 text-sm">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
                        <p>
                          <span className="font-medium">Requires a licensed IBM FileNet P8 Content Platform Engine.</span>{' '}
                          OpenCrawling does not include IBM FileNet or any IBM licence. The Content Services GraphQL API must be
                          deployed, and the crawl account needs read access to the object store. See the connector&apos;s README,
                          section Prerequisites.
                        </p>
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Content Services GraphQL Endpoint</label>
                        <input
                          {...register('configuration.url', { required: true })}
                          placeholder="https://cpe.example.com/content-services-graphql/graphql"
                          defaultValue="https://localhost:9443/content-services-graphql/graphql"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Object Store (Symbolic Name or ID)</label>
                        <input
                          {...register('configuration.objectStore', { required: true })}
                          placeholder="OS1"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Authentication Type</label>
                        <select
                          {...register('configuration.authType')}
                          defaultValue="basic"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="basic">Basic (Username / Password)</option>
                          <option value="oauth2">OAuth2 Client Credentials (IAM / Zen)</option>
                          <option value="bearer">Bearer Access Token</option>
                          <option value="ltpa">LTPA Token (WebSphere Liberty)</option>
                        </select>
                      </div>
                      {filenetAuthType === 'basic' && (
                        <>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Username</label>
                        <input
                          {...register('configuration.username')}
                          placeholder="p8crawler"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Password</label>
                        <input
                          type="password"
                          {...register('configuration.password')}
                          placeholder=""
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                        </>
                      )}
                      {filenetAuthType === 'oauth2' && (
                        <>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">OAuth2 Token URL</label>
                        <input
                          {...register('configuration.oauth2TokenUrl')}
                          placeholder="https://cpd.example.com/idprovider/v1/auth/identitytoken"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">OAuth2 Client ID</label>
                        <input
                          {...register('configuration.oauth2ClientId')}
                          placeholder="opencrawling-filenet-client"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">OAuth2 Client Secret</label>
                        <input
                          type="password"
                          {...register('configuration.oauth2ClientSecret')}
                          placeholder="client secret"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">OAuth2 Scope (Optional)</label>
                        <input
                          {...register('configuration.oauth2Scope')}
                          placeholder="openid"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                        </>
                      )}
                      {filenetAuthType === 'bearer' && (
                        <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Access Token</label>
                        <input
                          type="password"
                          {...register('configuration.oauth2AccessToken')}
                          placeholder="eyJ…"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      )}
                      {filenetAuthType === 'ltpa' && (
                        <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">LtpaToken2 Cookie Value</label>
                        <input
                          type="password"
                          {...register('configuration.ltpaToken')}
                          placeholder=""
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      )}
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Crawl Mode</label>
                        <select
                          {...register('configuration.crawlMode')}
                          defaultValue="query"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="query">FileNet SQL Query (Document Classes)</option>
                          <option value="folder">Folder Containment (INSUBFOLDER)</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Version Policy</label>
                        <select
                          {...register('configuration.versionMode')}
                          defaultValue="current"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="current">Current Version Only</option>
                          <option value="released">Latest Released (Major) Version</option>
                          <option value="all">All Versions</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Document Classes (Comma-Separated)</label>
                        <input
                          {...register('configuration.documentClasses')}
                          placeholder="Document, LoanDocument"
                          defaultValue="Document"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      {filenetCrawlMode === 'folder' && (
                        <>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Folder Path</label>
                        <input
                          {...register('configuration.rootFolderPath')}
                          placeholder="/Mortgages"
                          defaultValue="/"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeSubfoldersFileNet"
                          {...register('configuration.includeSubfolders')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeSubfoldersFileNet" className="text-sm font-medium cursor-pointer">
                          Include Subfolders
                        </label>
                      </div>
                        </>
                      )}
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Additional CE SQL Condition (Optional)</label>
                        <input
                          {...register('configuration.where')}
                          placeholder="[DocumentTitle] LIKE 'Loan%' AND [DateCreated] > 20260101T000000Z"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Full FileNet SQL Statement (Optional, Replaces the Classes)</label>
                        <input
                          {...register('configuration.sqlQuery')}
                          placeholder="SELECT Id FROM LoanDocument WHERE [LoanStatus] = 'ACTIVE'"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Read Access Mask</label>
                        <input
                          {...register('configuration.readAccessMask')}
                          placeholder="131201 = view content level"
                          defaultValue="131201"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">#AUTHENTICATED-USERS Identity</label>
                        <input
                          {...register('configuration.authenticatedUsersIdentity')}
                          placeholder="#authenticated-users"
                          defaultValue="#authenticated-users"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Principal Format</label>
                        <select
                          {...register('configuration.principalFormat')}
                          defaultValue="name"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="name">Grantee Names as Returned (Lower-Cased)</option>
                          <option value="short">Short Names (cn=Finance,… → finance)</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Documents with Markings</label>
                        <select
                          {...register('configuration.markedDocuments')}
                          defaultValue="exclude"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="exclude">Leave Out (Safe Default)</option>
                          <option value="index">Index, Applying Marking Denies from Permissions</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Fallback Principals (Comma-Separated)</label>
                        <input
                          {...register('configuration.fallbackPrincipals')}
                          placeholder="granted read when no permission is readable; empty = nobody"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Deletion Tracking</label>
                        <select
                          {...register('configuration.deletionTracking')}
                          defaultValue="reconcile"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="reconcile">Reconcile Current Ids (No Auditing Needed)</option>
                          <option value="audit">DeletionEvent Audit Log</option>
                          <option value="none">None</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Batch Size (Page Size)</label>
                        <input
                          {...register('configuration.batchSize')}
                          placeholder="100"
                          defaultValue="100"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Parallel Documents</label>
                        <input
                          {...register('configuration.parallelism')}
                          placeholder="4"
                          defaultValue="4"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max Content Size (Bytes)</label>
                        <input
                          {...register('configuration.maxContentSizeBytes')}
                          placeholder="52428800"
                          defaultValue="52428800"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeContentStreamFileNet"
                          {...register('configuration.includeContentStream')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeContentStreamFileNet" className="text-sm font-medium cursor-pointer">
                          Include Content Streams
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeAclsFileNet"
                          {...register('configuration.includeAcls')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeAclsFileNet" className="text-sm font-medium cursor-pointer">
                          Include Permissions & ACLs
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="includeCustomPropertiesFileNet"
                          {...register('configuration.includeCustomProperties')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="includeCustomPropertiesFileNet" className="text-sm font-medium cursor-pointer">
                          Include Class Properties
                        </label>
                      </div>
                      <div className="space-y-2 flex items-center gap-2 pt-6">
                        <input
                          type="checkbox"
                          id="incrementalEnabledFileNet"
                          {...register('configuration.incrementalEnabled')}
                          defaultChecked={true}
                          className="rounded border-border text-primary focus:ring-primary/50"
                        />
                        <label htmlFor="incrementalEnabledFileNet" className="text-sm font-medium cursor-pointer">
                          Incremental Crawl (Watermark, Skip Unchanged)
                        </label>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Incremental State Directory</label>
                        <input
                          {...register('configuration.stateDirectory')}
                          placeholder="data/filenet-state"
                          defaultValue="data/filenet-state"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* Apache Iceberg Catalog Table */}
                  {selectedClass === 'org.opencrawling.iceberg.IcebergRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Catalog Type</label>
                        <select 
                          {...register('configuration.catalogType', { required: true })}
                          defaultValue="in-memory"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none bg-card"
                        >
                          <option value="in-memory">In-Memory (Local Testing)</option>
                          <option value="rest">REST Catalog</option>
                          <option value="hive">Hive Metastore (Thrift)</option>
                          <option value="hadoop">Hadoop Catalog (Local/HDFS)</option>
                          <option value="glue">AWS Glue</option>
                        </select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Catalog URI (Optional)</label>
                        <input 
                          {...register('configuration.catalogUri')}
                          placeholder="e.g. http://localhost:8181 or thrift://localhost:9083"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Warehouse Location</label>
                        <input 
                          {...register('configuration.warehouse', { required: true })}
                          placeholder="s3a://bucket/warehouse or local path"
                          defaultValue="tmp/iceberg-warehouse"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Default Table Name (Optional)</label>
                        <input 
                          {...register('configuration.tableName')}
                          placeholder="e.g. db.table"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">ID Column (Optional)</label>
                        <input 
                          {...register('configuration.idColumn')}
                          placeholder="e.g. id"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>

                      <div className="col-span-2 p-3 bg-sky-500/10 border border-sky-500/20 rounded-md text-xs text-sky-300 flex items-start gap-2">
                        <Database className="w-4 h-4 text-sky-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-sky-200">Lakehouse Collocated Lineage: </span>
                          Automatically stamps <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">iceberg_table_name</code>, <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">iceberg_record_id</code>, <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">iceberg_snapshot_id</code>, and <code className="bg-sky-900/40 px-1 py-0.5 rounded text-sky-200">iceberg_table_location</code> for zero-lag joins and data warehouse governance pushdown.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Relational Database (JDBC) Repository */}
                  {selectedClass === 'org.opencrawling.jdbc.JdbcRepositoryConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">Database Engine Preset</label>
                          <select
                            onChange={(e) => {
                              const val = e.target.value
                              if (val === 'postgres') {
                                setValue('configuration.driverClassName', 'org.postgresql.Driver')
                                setValue('configuration.url', 'jdbc:postgresql://localhost:5432/opencrawling')
                              } else if (val === 'mysql') {
                                setValue('configuration.driverClassName', 'com.mysql.cj.jdbc.Driver')
                                setValue('configuration.url', 'jdbc:mysql://localhost:3306/opencrawling')
                              } else if (val === 'oracle') {
                                setValue('configuration.driverClassName', 'oracle.jdbc.OracleDriver')
                                setValue('configuration.url', 'jdbc:oracle:thin:@localhost:1521:xe')
                              } else if (val === 'sqlserver') {
                                setValue('configuration.driverClassName', 'com.microsoft.sqlserver.jdbc.SQLServerDriver')
                                setValue('configuration.url', 'jdbc:sqlserver://localhost:1433;databaseName=opencrawling')
                              } else if (val === 'sqlite') {
                                setValue('configuration.driverClassName', 'org.sqlite.JDBC')
                                setValue('configuration.url', 'jdbc:sqlite:/tmp/opencrawling.db')
                              } else if (val === 'h2') {
                                setValue('configuration.driverClassName', 'org.h2.Driver')
                                setValue('configuration.url', 'jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1')
                              }
                            }}
                            defaultValue="postgres"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none bg-card"
                          >
                            <option value="postgres">PostgreSQL</option>
                            <option value="mysql">MySQL / MariaDB</option>
                            <option value="oracle">Oracle Database</option>
                            <option value="sqlserver">Microsoft SQL Server</option>
                            <option value="sqlite">SQLite</option>
                            <option value="h2">H2 (In-Memory / Testing)</option>
                            <option value="custom">Custom JDBC Driver</option>
                          </select>
                        </div>
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">JDBC URL</label>
                          <input 
                            {...register('configuration.url', { required: true })}
                            placeholder="e.g. jdbc:postgresql://localhost:5432/crm_prod"
                            defaultValue="jdbc:postgresql://localhost:5432/opencrawling"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Driver Class Name (Optional)</label>
                          <input 
                            {...register('configuration.driverClassName')}
                            placeholder="e.g. org.postgresql.Driver"
                            defaultValue="org.postgresql.Driver"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Crawl Mode</label>
                          <select 
                            {...register('configuration.crawlMode')}
                            defaultValue="TABLE"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none bg-card"
                          >
                            <option value="TABLE">Table / View Ingestion Mode</option>
                            <option value="QUERY">Custom SQL Query Mode</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.username')}
                            placeholder="e.g. db_user"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.password')}
                            placeholder="••••••••"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Table Name</label>
                          <input 
                            {...register('configuration.tableName')}
                            placeholder="e.g. support_tickets"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Schema Name (Optional)</label>
                          <input 
                            {...register('configuration.schemaName')}
                            placeholder="e.g. public"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Primary Key Column(s)</label>
                          <input 
                            {...register('configuration.primaryKeyColumns')}
                            placeholder="e.g. id or ticket_id,tenant_id"
                            defaultValue="id"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Title Column</label>
                          <input 
                            {...register('configuration.titleColumn')}
                            placeholder="e.g. title or subject"
                            defaultValue="title"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">Custom SQL Query (When Crawl Mode is QUERY)</label>
                          <textarea 
                            {...register('configuration.querySql')}
                            placeholder="SELECT t.id, t.title, t.status, t.owner_id, t.is_deleted FROM support_tickets t WHERE (:lastCrawledTime IS NULL OR t.updated_at >= :lastCrawledTime)"
                            rows={3}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">BLOB Column Name (Optional)</label>
                          <input 
                            {...register('configuration.blobColumnName')}
                            placeholder="e.g. file_data or attachment_blob"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">File Name Column (Optional)</label>
                          <input 
                            {...register('configuration.fileNameColumn')}
                            placeholder="e.g. file_name"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">MIME Type Column (Optional)</label>
                          <input 
                            {...register('configuration.mimeTypeColumn')}
                            placeholder="e.g. content_type"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Excluded Columns (Comma-separated)</label>
                          <input 
                            {...register('configuration.excludedColumns')}
                            placeholder="e.g. password_hash, internal_secret"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Soft-Delete Column (Optional)</label>
                          <input 
                            {...register('configuration.softDeleteColumn')}
                            placeholder="e.g. is_deleted"
                            defaultValue="is_deleted"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Soft-Delete Tombstone Value</label>
                          <input 
                            {...register('configuration.softDeleteValue')}
                            placeholder="e.g. true or PURGED"
                            defaultValue="true"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">User Column(s) (Comma-separated)</label>
                          <input 
                            {...register('configuration.userColumns')}
                            placeholder="e.g. owner_id, assignee"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Group Column(s) (Comma-separated)</label>
                          <input 
                            {...register('configuration.groupColumns')}
                            placeholder="e.g. department_id, security_group"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Tenant Column (Optional)</label>
                          <input 
                            {...register('configuration.tenantColumn')}
                            placeholder="e.g. tenant_id"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Fetch Size (Cursor Streaming)</label>
                          <input 
                            type="number"
                            {...register('configuration.fetchSize')}
                            defaultValue={1000}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">Narrativization Template (Mustache, Optional)</label>
                          <textarea 
                            {...register('configuration.narrativizationTemplate')}
                            rows={3}
                            placeholder="e.g. # Customer Record: {{first_name}} {{last_name}}&#10;Email: {{email}}&#10;Account Status: {{status}}"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                          <p className="text-xs text-muted-foreground">
                            Mustache template used to narrativize tabular rows into natural language markdown for Tabular RAG. When BLOB columns are present, binary data (images, PDFs, documents) bypasses this template to stream directly for vector embeddings.
                          </p>
                        </div>
                      </div>

                      <div className="p-3 bg-amber-500/10 border border-amber-500/20 rounded-md text-xs text-amber-300 flex items-start gap-2">
                        <Database className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
                        <div>
                          <span className="font-semibold text-amber-200">Relational Ingestion & Tabular RAG: </span>
                          Leverages Java 25 Virtual Threads with server-side JDBC cursor streaming. Supports automated table schema inspection via <code className="bg-amber-900/40 px-1 py-0.5 rounded text-amber-200">getSchema()</code> for Spring AI Narrativization Copilot, zero-trust column-based ACL mappings, and decoupled Claim-Check BLOB offloading.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* gRPC Push Repository Connector */}
                  {selectedClass === 'org.opencrawling.push.grpc.GrpcPushRepositoryConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4 font-sans text-foreground">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Listener Port</label>
                        <input 
                          type="number"
                          {...register('configuration.port', { required: true })}
                          placeholder="9096"
                          defaultValue="9096"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Max Message Size (MB)</label>
                        <input 
                          type="number"
                          {...register('configuration.maxMessageSizeMb', { required: true })}
                          placeholder="64"
                          defaultValue="64"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Inline Binary Threshold (KB)</label>
                        <input 
                          type="number"
                          {...register('configuration.inlineBinaryThresholdKb')}
                          placeholder="512"
                          defaultValue="512"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Authentication Token (Optional Bearer Token)</label>
                        <input 
                          type="password"
                          {...register('configuration.token')}
                          placeholder="Bearer authentication token"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Require Client Auth (mTLS)</label>
                        <select 
                          {...register('configuration.clientAuth')}
                          defaultValue="NONE"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        >
                          <option value="NONE">NONE - Plain TLS or Unauthenticated</option>
                          <option value="OPTIONAL">OPTIONAL - Validate Client Cert if Presented</option>
                          <option value="REQUIRE">REQUIRE - Enforce Mutual TLS (mTLS)</option>
                        </select>
                      </div>
                      <div className="space-y-2 flex items-center pt-6">
                        <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                          <input 
                            type="checkbox"
                            {...register('configuration.tlsEnabled')}
                            className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                          />
                          Enable TLS Transport Encryption
                        </label>
                      </div>

                      <div className="col-span-2 p-3 bg-violet-500/10 border border-violet-500/20 rounded-md text-xs text-violet-300 flex items-start gap-2">
                        <Network className="w-4 h-4 flex-shrink-0 mt-0.5 text-violet-400" />
                        <div>
                          <p className="font-semibold text-violet-200">High-Throughput Binary Protobuf Ingestion</p>
                          <p className="mt-0.5 opacity-90">
                            The gRPC Push Connector accepts unary, client-streaming, and bi-directional streaming OIS Protobuf messages over HTTP/2.
                            Incoming payloads exceeding the inline threshold are automatically offloaded to the ClaimCheckStore.
                          </p>
                        </div>
                      </div>

                      <div className="col-span-2 flex justify-end">
                        <button
                          type="button"
                          onClick={handleCheckConnection}
                          disabled={isCheckingConnection}
                          className="btn-secondary flex items-center gap-2 text-xs py-1.5 px-3 border border-violet-500/30 text-violet-300 hover:bg-violet-500/10"
                        >
                          {isCheckingConnection ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5 text-violet-400" />}
                          Run Live Ping Diagnostic Test
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Hyland OpenArch Federated Repository Connector */}
                  {selectedClass === 'org.opencrawling.openarch.repository.OpenArchRepositoryConnector' && (
                    <div className="space-y-4 font-sans text-foreground">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">OpenArch Endpoint URL</label>
                          <input 
                            {...register('configuration.endpointUrl', { required: true })}
                            placeholder="http://localhost:8080"
                            defaultValue="http://localhost:8080"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Repository ID</label>
                          <input 
                            {...register('configuration.repositoryId')}
                            defaultValue="default"
                            placeholder="default"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Crawl Mode</label>
                          <select 
                            {...register('configuration.crawlMode')}
                            defaultValue="QUERY"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="QUERY">HXQL Query Mode</option>
                            <option value="FOLDER">Folder Hierarchy Mode</option>
                          </select>
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.username')}
                            defaultValue="Administrator"
                            placeholder="Administrator"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.password')}
                            defaultValue="Administrator"
                            placeholder="Administrator"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        {openarchCrawlMode === 'QUERY' ? (
                          <div className="space-y-2 col-span-2">
                            <label className="text-sm font-medium">HXQL Query</label>
                            <input 
                              {...register('configuration.hxqlQuery')}
                              defaultValue="SELECT * FROM SysFile"
                              placeholder="SELECT * FROM SysFile"
                              className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                            />
                          </div>
                        ) : (
                          <>
                            <div className="space-y-2">
                              <label className="text-sm font-medium">Root Folder ID</label>
                              <input 
                                {...register('configuration.rootFolderId')}
                                defaultValue="00000000-0000-0000-0000-000000000000"
                                placeholder="00000000-0000-0000-0000-000000000000"
                                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                              />
                            </div>
                            <div className="space-y-2 flex items-center pt-6">
                              <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                                <input 
                                  type="checkbox"
                                  {...register('configuration.includeSubfolders')}
                                  defaultChecked={true}
                                  className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                                />
                                Include Subfolders Recursively
                              </label>
                            </div>
                          </>
                        )}

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Batch Size (Pages)</label>
                          <input 
                            type="number"
                            {...register('configuration.batchSize')}
                            defaultValue={100}
                            placeholder="100"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2 flex items-center pt-6">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.incremental')}
                              defaultChecked={false}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Incremental Crawling (sys_modified filtering)
                          </label>
                        </div>

                        <div className="space-y-2 flex items-center pt-2">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.includeAcls')}
                              defaultChecked={true}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Extract &amp; Map Zero-Trust ACLs
                          </label>
                        </div>

                        <div className="space-y-2 flex items-center pt-2">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.includeContentStream')}
                              defaultChecked={true}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Stream Binary Blobs (/api/download)
                          </label>
                        </div>

                        <div className="col-span-2 p-3 bg-indigo-500/10 border border-indigo-500/20 rounded-md text-xs text-indigo-300 flex items-start gap-2">
                          <Layers className="w-4 h-4 flex-shrink-0 mt-0.5 text-indigo-400" />
                          <div>
                            <p className="font-semibold text-indigo-200">Hyland OpenArch Federated Source</p>
                            <p className="mt-0.5 opacity-90">
                              Harvesters query OpenArch repository records via HXQL or folder traversal, preserving Zero-Trust ACLs and offloading binaries via Java 25 Virtual Threads.
                            </p>
                          </div>
                        </div>

                        <div className="col-span-2 flex justify-end">
                          <button
                            type="button"
                            onClick={handleCheckConnection}
                            disabled={isCheckingConnection}
                            className="btn-secondary flex items-center gap-2 text-xs py-1.5 px-3 border border-indigo-500/30 text-indigo-300 hover:bg-indigo-500/10"
                          >
                            {isCheckingConnection ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />}
                            Test OpenArch Connectivity
                          </button>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* PGVector Store */}
                  {selectedClass === 'org.opencrawling.vector.VectorOutputConnector' && (
                    <div className="space-y-4 font-sans text-foreground">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">PostgreSQL JDBC URL</label>
                          <input 
                            {...register('configuration.pgVectorUrl')}
                            placeholder="jdbc:postgresql://localhost:5432/opencrawling"
                            defaultValue="jdbc:postgresql://127.0.0.1:5432/opencrawling"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.pgVectorUsername')}
                            placeholder="opencrawling"
                            defaultValue="opencrawling"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.pgVectorPassword')}
                            placeholder="Database password"
                            defaultValue="opencrawling_password"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Table Name</label>
                          <input 
                            {...register('configuration.pgVectorTableName')}
                            placeholder="vector_store"
                            defaultValue="vector_store"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Vector Dimensions</label>
                          <input 
                            type="number"
                            {...register('configuration.pgVectorDimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Milvus Vector Store */}
                  {selectedClass === 'org.opencrawling.milvus.MilvusOutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Milvus URI</label>
                          <input 
                            {...register('configuration.milvusUri')}
                            placeholder="http://localhost:19530"
                            defaultValue="http://localhost:19530"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Token / Authentication</label>
                          <input
                            type="password"
                            {...register('configuration.milvusToken')}
                            placeholder="root:Milvus"
                            defaultValue="root:Milvus"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Collection Name</label>
                          <input 
                            {...register('configuration.milvusCollection')}
                            placeholder="enterprise_kb"
                            defaultValue="enterprise_kb"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Vector Field Name</label>
                          <input 
                            {...register('configuration.milvusVectorField')}
                            placeholder="embeddings"
                            defaultValue="embeddings"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Dimensions</label>
                          <input 
                            type="number"
                            {...register('configuration.milvusDimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Metric Type</label>
                          <select 
                            {...register('configuration.milvusMetricType')}
                            defaultValue="COSINE"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="COSINE">COSINE (Default)</option>
                            <option value="L2">L2 (Euclidean)</option>
                            <option value="IP">IP (Inner Product)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Index Type</label>
                          <select 
                            {...register('configuration.milvusIndexType')}
                            defaultValue="HNSW"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="AUTOINDEX">AUTOINDEX (Zilliz Cloud)</option>
                            <option value="HNSW">HNSW (Recommended)</option>
                            <option value="IVF_FLAT">IVF_FLAT</option>
                            <option value="FLAT">FLAT</option>
                          </select>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Qdrant Vector Store */}
                  {selectedClass === 'org.opencrawling.qdrant.QdrantOutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Host</label>
                          <input
                            {...register('configuration.qdrantHost')}
                            placeholder="localhost"
                            defaultValue="localhost"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">gRPC Port</label>
                          <input
                            type="number"
                            {...register('configuration.qdrantPort', { valueAsNumber: true })}
                            placeholder="6334"
                            defaultValue={6334}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">API Key</label>
                          <input
                            type="password"
                            {...register('configuration.qdrantApiKey')}
                            placeholder="(optional, for Qdrant Cloud)"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Collection Name</label>
                          <input
                            {...register('configuration.qdrantCollection')}
                            placeholder="enterprise_kb"
                            defaultValue="enterprise_kb"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Dimensions</label>
                          <input
                            type="number"
                            {...register('configuration.qdrantDimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Distance Metric</label>
                          <select
                            {...register('configuration.qdrantDistance')}
                            defaultValue="COSINE"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="COSINE">Cosine (Default)</option>
                            <option value="DOT">Dot Product</option>
                            <option value="EUCLID">Euclidean</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Quantization</label>
                          <select
                            {...register('configuration.qdrantQuantization')}
                            defaultValue="NONE"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="NONE">None (Default)</option>
                            <option value="SCALAR_INT8">Scalar (INT8)</option>
                            <option value="BINARY">Binary</option>
                          </select>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* OpenSearch 2.x Output Store */}
                  {selectedClass === 'org.opencrawling.opensearch2.OpenSearch2OutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OpenSearch 2.x URIs</label>
                          <input 
                            {...register('configuration.opensearch2Uris')}
                            placeholder="http://localhost:9200"
                            defaultValue="http://localhost:9200"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Index Name</label>
                          <input 
                            {...register('configuration.opensearch2IndexName')}
                            placeholder="enterprise_kb"
                            defaultValue="enterprise_kb"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.opensearch2Username')}
                            placeholder="admin"
                            defaultValue="admin"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.opensearch2Password')}
                            placeholder="admin"
                            defaultValue="admin"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Dimensions</label>
                          <input 
                            type="number"
                            {...register('configuration.opensearch2Dimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>
                    </div>
                  )}

                  {/* OpenSearch 3.x Output Store */}
                  {selectedClass === 'org.opencrawling.opensearch3.OpenSearch3OutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">OpenSearch 3.x URIs</label>
                          <input 
                            {...register('configuration.opensearch3Uris')}
                            placeholder="http://localhost:9200"
                            defaultValue="http://localhost:9200"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Index Name</label>
                          <input 
                            {...register('configuration.opensearch3IndexName')}
                            placeholder="enterprise_kb"
                            defaultValue="enterprise_kb"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.opensearch3Username')}
                            placeholder="admin"
                            defaultValue="admin"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.opensearch3Password')}
                            placeholder="admin"
                            defaultValue="admin"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Dimensions</label>
                          <input 
                            type="number"
                            {...register('configuration.opensearch3Dimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Vespa Hybrid Search Store */}
                  {selectedClass === 'org.opencrawling.vespa.VespaOutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Endpoint</label>
                          <input
                            {...register('configuration.vespaEndpoint', { required: true })}
                            placeholder="http://localhost:8080"
                            defaultValue="http://localhost:8080"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                          <p className="text-xs text-muted-foreground">The Vespa container/search endpoint (Document &amp; Search API, port 8080 by default).</p>
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Namespace</label>
                          <input
                            {...register('configuration.vespaNamespace')}
                            placeholder="opencrawling"
                            defaultValue="opencrawling"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                          <p className="text-xs text-muted-foreground">Document ID namespace used for every fed chunk.</p>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Default Document Type</label>
                          <input
                            {...register('configuration.vespaDocumentType')}
                            placeholder="opencrawling_chunk"
                            defaultValue="opencrawling_chunk"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                          <p className="text-xs text-muted-foreground">Fallback only - used when an embedding's dimension isn't 384, 768, or 1024 (see below).</p>
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Fallback Dimensions</label>
                          <input
                            type="number"
                            {...register('configuration.vespaDimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Timeout (Seconds)</label>
                          <input
                            type="number"
                            {...register('configuration.vespaTimeoutSeconds', { valueAsNumber: true })}
                            placeholder="30"
                            defaultValue={30}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="p-3 rounded-lg border border-violet-500/20 bg-violet-500/5 flex items-start gap-3">
                        <Layers className="w-4 h-4 flex-shrink-0 text-violet-400 mt-0.5" />
                        <p className="text-xs text-violet-200/80">
                          <span className="font-semibold text-violet-300">Dynamic multi-dimension routing:</span> each chunk is routed automatically to a dedicated
                          document type (384 / 768 / 1024) based on its actual embedding length, so <span className="font-mono">all-minilm</span>, <span className="font-mono">nomic-embed-text</span>, and <span className="font-mono">mxbai-embed-large</span> can
                          all feed this store side by side - no config change or redeploy needed when switching embedding models.
                        </p>
                      </div>

                      <div className="pt-2 border-t border-border">
                        <div className="flex items-center gap-2 pt-3">
                          <input
                            type="checkbox"
                            id="vespaTlsEnabled"
                            checked={vespaTlsEnabledBool}
                            onChange={(e) => setValue('configuration.vespaTlsEnabled', e.target.checked, { shouldDirty: true })}
                            className="rounded border-border text-primary focus:ring-primary/50"
                          />
                          <label htmlFor="vespaTlsEnabled" className="text-sm font-medium cursor-pointer">
                            Enable mTLS (Vespa Cloud)
                          </label>
                        </div>

                        {vespaTlsEnabledBool && (
                          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-3 animate-in fade-in duration-200">
                            <div className="space-y-2">
                              <label className="text-sm font-medium">Client Certificate Path</label>
                              <input
                                {...register('configuration.vespaTlsCertificate')}
                                placeholder="/path/to/cert.pem"
                                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                              />
                            </div>
                            <div className="space-y-2">
                              <label className="text-sm font-medium">Private Key Path</label>
                              <input
                                {...register('configuration.vespaTlsPrivateKey')}
                                placeholder="/path/to/key.pem"
                                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                              />
                            </div>
                            <div className="space-y-2">
                              <label className="text-sm font-medium">CA Certificates Path</label>
                              <input
                                {...register('configuration.vespaTlsCaCertificates')}
                                placeholder="/path/to/ca.pem (optional)"
                                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                              />
                            </div>
                          </div>
                        )}
                      </div>

                      <VespaModelInsights endpoint={(vespaEndpointValue as string) || 'http://localhost:8080'} />
                    </div>
                  )}

                  {/* Elasticsearch */}
                  {selectedClass === 'org.opencrawling.agents.output.elasticsearch.ElasticsearchConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Elasticsearch Hosts (Comma-separated)</label>
                        <input 
                          {...register('configuration.esHosts', { required: true })}
                          placeholder="http://localhost:9200"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Index Name</label>
                        <input 
                          {...register('configuration.esIndex', { required: true })}
                          placeholder="opencrawling-vectors"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                    </div>
                  )}

                  {/* Apache Solr Output Connector */}
                  {selectedClass === 'org.opencrawling.solr.SolrOutputConnector' && (
                    <div className="space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Solr Mode</label>
                          <select
                            {...register('configuration.solrMode')}
                            defaultValue="standalone"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="standalone">Standalone Solr</option>
                            <option value="cloud">SolrCloud (ZooKeeper)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Solr Base URL</label>
                          <input
                            {...register('configuration.solrUrl', { required: true })}
                            placeholder="http://localhost:8983/solr"
                            defaultValue="http://localhost:8983/solr"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      {solrMode === 'cloud' && (
                        <div className="space-y-2">
                          <label className="text-sm font-medium">ZooKeeper Host(s)</label>
                          <input
                            {...register('configuration.solrZkHost')}
                            placeholder="localhost:2181"
                            defaultValue="localhost:2181"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                          <p className="text-xs text-muted-foreground">ZooKeeper connection string for SolrCloud mode (comma separated).</p>
                        </div>
                      )}

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Collection Name</label>
                          <input
                            {...register('configuration.solrCollection', { required: true })}
                            placeholder="enterprise_kb"
                            defaultValue="enterprise_kb"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Vector Dimensions</label>
                          <input
                            type="number"
                            {...register('configuration.solrDimensions', { valueAsNumber: true })}
                            placeholder="1024"
                            defaultValue={1024}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Similarity Metric</label>
                          <select
                            {...register('configuration.solrSimilarity')}
                            defaultValue="cosine"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="cosine">Cosine</option>
                            <option value="dot_product">Dot Product</option>
                            <option value="euclidean">Euclidean</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Vector Encoding (Solr 10)</label>
                          <select
                            {...register('configuration.solrVectorEncoding')}
                            defaultValue="FLOAT32"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          >
                            <option value="FLOAT32">FLOAT32</option>
                            <option value="BYTE">BYTE</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Quantization (Solr 10)</label>
                          <select
                            {...register('configuration.solrQuantization')}
                            defaultValue="none"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          >
                            <option value="none">None (Full Precision)</option>
                            <option value="scalar">Scalar Quantization (SQ)</option>
                            <option value="binary">Binary Quantization (BQ)</option>
                          </select>
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">HNSW Max Connections</label>
                          <input
                            type="number"
                            {...register('configuration.solrHnswMaxConnections', { valueAsNumber: true })}
                            placeholder="16"
                            defaultValue={16}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">HNSW Beam Width</label>
                          <input
                            type="number"
                            {...register('configuration.solrHnswBeamWidth', { valueAsNumber: true })}
                            placeholder="100"
                            defaultValue={100}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">efSearch (Solr 10)</label>
                          <input
                            type="number"
                            {...register('configuration.solrEfSearch', { valueAsNumber: true })}
                            placeholder="100"
                            defaultValue={100}
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Commit Within (ms)</label>
                        <input
                          type="number"
                          {...register('configuration.solrCommitWithinMs', { valueAsNumber: true })}
                          placeholder="1000"
                          defaultValue={1000}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* Luxir Output Connector */}
                  {selectedClass === 'org.opencrawling.luxir.LuxirOutputConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Luxir HTTP Endpoint</label>
                        <input 
                          {...register('configuration.luxirEndpoint', { required: true })}
                          placeholder="http://localhost:9400"
                          defaultValue="http://localhost:9400"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">HTTP/JSON API endpoint for Luxir Search Engine (default port 9400).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Collection Name</label>
                        <input 
                          {...register('configuration.luxirCollection', { required: true })}
                          placeholder="opencrawling"
                          defaultValue="opencrawling"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Target collection name in Luxir (auto-created on initialization).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Vector Field Name</label>
                        <input 
                          {...register('configuration.luxirVectorField')}
                          placeholder="embedding_v"
                          defaultValue="embedding_v"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Vector field name (supports Luxir _v naming convention).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Vector Dimensions</label>
                        <input 
                          type="number"
                          {...register('configuration.luxirDimensions', { valueAsNumber: true })}
                          placeholder="1024"
                          defaultValue={1024}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Dense embedding dimensionality (e.g. 384 for MiniLM, 768 for Nomic, 1024 for mxbai).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Similarity Metric</label>
                        <select 
                          {...register('configuration.luxirSimilarity')}
                          defaultValue="cosine"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        >
                          <option value="cosine">Cosine (cosine)</option>
                          <option value="l2">Euclidean (l2)</option>
                          <option value="ip">Inner Product (ip)</option>
                        </select>
                        <p className="text-xs text-muted-foreground">Vector similarity distance function used for kNN indexing.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Timeout (seconds)</label>
                        <input 
                          type="number"
                          {...register('configuration.luxirTimeoutSeconds', { valueAsNumber: true })}
                          placeholder="30"
                          defaultValue={30}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">HTTP socket and connection timeout.</p>
                      </div>
                    </div>
                  )}

                  {/* Apache SeaTunnel Output Connector */}
                  {selectedClass === 'org.opencrawling.seatunnel.SeaTunnelOutputConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">SeaTunnel Zeta REST URL</label>
                        <input 
                          {...register('configuration.seaTunnelRestUrl', { required: true })}
                          placeholder="http://localhost:8080"
                          defaultValue="http://localhost:8080"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">HTTP REST API endpoint for SeaTunnel Zeta Engine (default port 8080).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Pipeline Job Name</label>
                        <input 
                          {...register('configuration.seaTunnelJobName', { required: true })}
                          placeholder="opencrawling_ingestion_pipeline"
                          defaultValue="opencrawling_ingestion_pipeline"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Unique identifier for the SeaTunnel execution job.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Execution Job Mode</label>
                        <select 
                          {...register('configuration.seaTunnelJobMode')}
                          defaultValue="STREAMING"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        >
                          <option value="STREAMING">STREAMING (Continuous Real-time)</option>
                          <option value="BATCH">BATCH (Bounded Run)</option>
                        </select>
                        <p className="text-xs text-muted-foreground">Pipeline execution mode for SeaTunnel runtime.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Checkpoint Interval (ms)</label>
                        <input 
                          type="number"
                          {...register('configuration.seaTunnelCheckpointIntervalMs', { valueAsNumber: true })}
                          placeholder="5000"
                          defaultValue={5000}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Zeta engine distributed checkpointing interval in milliseconds.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Kafka Bootstrap Servers</label>
                        <input 
                          {...register('configuration.seaTunnelKafkaBootstrapServers')}
                          placeholder="localhost:9092"
                          defaultValue="localhost:9092"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Kafka brokers streaming OIS embedded chunks to SeaTunnel.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Kafka Topic</label>
                        <input 
                          {...register('configuration.seaTunnelKafkaTopic')}
                          placeholder="opencrawling-embedded"
                          defaultValue="opencrawling-embedded"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Stream topic source consumed by SeaTunnel pipeline.</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Target Fan-Out Sinks</label>
                        <input 
                          {...register('configuration.seaTunnelTargetSinks')}
                          placeholder="clickhouse,milvus"
                          defaultValue="clickhouse,milvus"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                        <p className="text-xs text-muted-foreground">Comma-separated target sinks (e.g. clickhouse, milvus, qdrant, iceberg, console).</p>
                      </div>

                      <div className="space-y-2">
                        <label className="text-sm font-medium">Auto-Submit Pipeline</label>
                        <select 
                          {...register('configuration.seaTunnelAutoSubmitJob')}
                          defaultValue="true"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        >
                          <option value="true">Enabled (Deploy on Connect)</option>
                          <option value="false">Disabled (Manual Deployment)</option>
                        </select>
                        <p className="text-xs text-muted-foreground">Automatically deploy generated HOCON job to Zeta cluster on startup.</p>
                      </div>
                    </div>
                  )}

                  {/* Apache Ozone Output Connector (Migration Mode only) */}
                  {selectedClass === 'org.opencrawling.ozone.OzoneOutputConnector' && (
                    <div className="space-y-4">
                      <p className="text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded-md px-3 py-2">
                        This connector only runs in Migration Mode: binaries are copied as-is with a companion OIS JSON sidecar. Jobs using it must select Migration Mode.
                      </p>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label htmlFor="ozone-client-type" className="text-sm font-medium">Transport</label>
                          <select
                            id="ozone-client-type"
                            {...register('configuration.clientType')}
                            defaultValue="NATIVE"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          >
                            <option value="NATIVE">NATIVE (ofs:// RPC to Ozone Manager)</option>
                            <option value="S3G">S3G (S3 Gateway over HTTP)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-volume" className="text-sm font-medium">Volume / Bucket</label>
                          <div className="flex gap-2">
                            <input id="ozone-volume" {...register('configuration.volume', { required: true })} defaultValue="s3v" placeholder="s3v"
                              className="w-1/2 bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                            <input aria-label="Bucket" {...register('configuration.bucket', { required: true })} defaultValue="migration" placeholder="migration"
                              className="w-1/2 bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                          </div>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-om-host" className="text-sm font-medium">Ozone Manager Host / Port (NATIVE)</label>
                          <div className="flex gap-2">
                            <input id="ozone-om-host" {...register('configuration.omHost')} defaultValue="localhost" placeholder="localhost"
                              className="flex-1 bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                            <input aria-label="Ozone Manager port" type="number" {...register('configuration.omPort')} defaultValue="9862" placeholder="9862"
                              className="w-28 bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                          </div>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-s3-endpoint" className="text-sm font-medium">S3 Gateway Endpoint (S3G)</label>
                          <input id="ozone-s3-endpoint" type="url" {...register('configuration.s3Endpoint')} defaultValue="http://localhost:9878" placeholder="http://localhost:9878"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-access-key" className="text-sm font-medium">S3 Access Key</label>
                          <input id="ozone-access-key" {...register('configuration.accessKey')} defaultValue="any" autoComplete="off"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-secret-key" className="text-sm font-medium">S3 Secret Key</label>
                          <input id="ozone-secret-key" type="password" {...register('configuration.secretKey')} defaultValue="any" autoComplete="new-password"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-key-strategy" className="text-sm font-medium">Key Strategy</label>
                          <select id="ozone-key-strategy" {...register('configuration.keyStrategy')} defaultValue="HIERARCHICAL"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="HIERARCHICAL">HIERARCHICAL (preserve source paths)</option>
                            <option value="FLAT">FLAT (id_filename)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="ozone-tombstone-action" className="text-sm font-medium">On OIS DELETE</label>
                          <select id="ozone-tombstone-action" {...register('configuration.tombstoneAction')} defaultValue="DELETE_KEY"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="DELETE_KEY">DELETE_KEY (purge binary + sidecar)</option>
                            <option value="ARCHIVE_TOMBSTONE">ARCHIVE_TOMBSTONE (purge + keep OIS tombstone)</option>
                          </select>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* AWS S3 Output Connector (Migration Mode only) */}
                  {selectedClass === 'org.opencrawling.s3.S3OutputConnector' && (
                    <div className="space-y-4">
                      <p className="text-xs text-orange-400 bg-orange-500/10 border border-orange-500/20 rounded-md px-3 py-2">
                        This connector only runs in Migration Mode: binaries are streamed as-is to Amazon S3 with companion OIS JSON metadata sidecars. Jobs using it must select Migration Mode.
                      </p>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label htmlFor="s3-bucket" className="text-sm font-medium">S3 Bucket Name</label>
                          <input id="s3-bucket" {...register('configuration.bucket', { required: true })} defaultValue="enterprise-migration-lake" placeholder="enterprise-migration-lake"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-region" className="text-sm font-medium">AWS Region</label>
                          <input id="s3-region" {...register('configuration.region')} defaultValue="us-east-1" placeholder="us-east-1"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-endpoint" className="text-sm font-medium">Endpoint Override (Optional for LocalStack / MinIO)</label>
                          <input id="s3-endpoint" type="url" {...register('configuration.endpointOverride')} placeholder="http://localhost:4566"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-path-style" className="text-sm font-medium">Path Style Access</label>
                          <select id="s3-path-style" {...register('configuration.pathStyleAccess')} defaultValue="false"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="false">Virtual-Hosted Style (bucket.s3.amazonaws.com)</option>
                            <option value="true">Path Style (endpoint/bucket - MinIO / LocalStack)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-creds-strategy" className="text-sm font-medium">Credentials Strategy</label>
                          <select id="s3-creds-strategy" {...register('configuration.credentialsStrategy')} defaultValue="DEFAULT_CHAIN"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="DEFAULT_CHAIN">DEFAULT_CHAIN (IRSA / IAM / Env / Profile)</option>
                            <option value="STATIC">STATIC (Access & Secret Keys)</option>
                            <option value="ASSUME_ROLE">ASSUME_ROLE (STS Cross-Account)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-access-key" className="text-sm font-medium">Access Key ID</label>
                          <input id="s3-access-key" {...register('configuration.accessKey')} placeholder="AKIA..." autoComplete="off"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-secret-key" className="text-sm font-medium">Secret Access Key</label>
                          <input id="s3-secret-key" type="password" {...register('configuration.secretKey')} placeholder="Secret..." autoComplete="new-password"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-session-token" className="text-sm font-medium">Session Token (Optional)</label>
                          <input id="s3-session-token" type="password" {...register('configuration.sessionToken')} placeholder="Session token..." autoComplete="off"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-role-arn" className="text-sm font-medium">AssumeRole ARN (for ASSUME_ROLE)</label>
                          <input id="s3-role-arn" {...register('configuration.assumeRoleArn')} placeholder="arn:aws:iam::123456789012:role/MigrationRole"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-role-ext-id" className="text-sm font-medium">AssumeRole External ID</label>
                          <input id="s3-role-ext-id" {...register('configuration.assumeRoleExternalId')} placeholder="Optional External ID"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-key-strategy" className="text-sm font-medium">Key Strategy</label>
                          <select id="s3-key-strategy" {...register('configuration.keyStrategy')} defaultValue="HIERARCHICAL"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="HIERARCHICAL">HIERARCHICAL (preserve folder structure)</option>
                            <option value="FLAT">FLAT (sanitized_id_filename)</option>
                            <option value="DATE_PARTITIONED">DATE_PARTITIONED (YYYY/MM/dd/id_filename)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-key-prefix" className="text-sm font-medium">Key Prefix</label>
                          <input id="s3-key-prefix" {...register('configuration.keyPrefix')} defaultValue="migration/v1/" placeholder="migration/v1/"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-storage-class" className="text-sm font-medium">Storage Class</label>
                          <select id="s3-storage-class" {...register('configuration.storageClass')} defaultValue="INTELLIGENT_TIERING"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="INTELLIGENT_TIERING">INTELLIGENT_TIERING (Recommended)</option>
                            <option value="STANDARD">STANDARD</option>
                            <option value="STANDARD_IA">STANDARD_IA (Infrequent Access)</option>
                            <option value="GLACIER_IR">GLACIER_IR (Instant Retrieval)</option>
                            <option value="GLACIER">GLACIER (Flexible Retrieval)</option>
                            <option value="DEEP_ARCHIVE">DEEP_ARCHIVE (Cold Storage)</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-encryption" className="text-sm font-medium">Server-Side Encryption</label>
                          <select id="s3-encryption" {...register('configuration.serverSideEncryption')} defaultValue="SSE_S3"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="SSE_S3">SSE_S3 (Amazon S3-Managed AES-256)</option>
                            <option value="SSE_KMS">SSE_KMS (AWS Key Management Service)</option>
                            <option value="NONE">NONE</option>
                          </select>
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-kms-key-id" className="text-sm font-medium">KMS Key ID / ARN (for SSE_KMS)</label>
                          <input id="s3-kms-key-id" {...register('configuration.kmsKeyId')} placeholder="arn:aws:kms:..."
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono" />
                        </div>
                        <div className="space-y-2">
                          <label htmlFor="s3-tombstone-action" className="text-sm font-medium">On OIS DELETE</label>
                          <select id="s3-tombstone-action" {...register('configuration.tombstoneAction')} defaultValue="DELETE_OBJECTS"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono">
                            <option value="DELETE_OBJECTS">DELETE_OBJECTS (purge binary + sidecar)</option>
                            <option value="ARCHIVE_TOMBSTONE">ARCHIVE_TOMBSTONE (keep OIS tombstone)</option>
                          </select>
                        </div>
                      </div>

                      {/* Hidden registered input for form submission */}
                      <input type="hidden" {...register('configuration.customHeaders')} />

                      {/* Custom HTTP Request Headers & Multi-Vendor Storage Presets */}
                      <div className="space-y-3 pt-4 border-t border-border">
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                          <div>
                            <h4 className="text-sm font-semibold text-foreground flex items-center gap-2">
                              <Settings2 className="h-4 w-4 text-orange-400" />
                              Custom HTTP Request Headers & Storage Presets
                            </h4>
                            <p className="text-xs text-muted-foreground mt-0.5">
                              Configure proprietary HTTP headers for Dell EMC ECS, IBM COS, Hitachi HCP, NetApp StorageGRID, or Cloudflare R2.
                            </p>
                          </div>

                          {/* Quick Preset Selector */}
                          <div className="flex items-center gap-2">
                            <select
                              id="s3-vendor-preset-select"
                              value={selectedPreset}
                              onChange={(e) => {
                                const val = e.target.value
                                setSelectedPreset(val)
                                if (val) {
                                  handleApplyPreset(val)
                                }
                              }}
                              className="bg-background border border-border rounded-md px-2.5 py-1.5 text-xs focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                            >
                              <option value="">-- Apply Vendor Preset --</option>
                              {S3_VENDOR_PRESETS.map((preset) => (
                                <option key={preset.id} value={preset.id}>
                                  {preset.name}
                                </option>
                              ))}
                            </select>
                          </div>
                        </div>

                        {/* Dynamic Key-Value Table */}
                        <div className="space-y-2">
                          {s3CustomHeaders.length > 0 && (
                            <div className="border border-border rounded-md overflow-hidden bg-card/40">
                              <table className="w-full text-xs">
                                <thead className="bg-muted/50 border-b border-border">
                                  <tr>
                                    <th className="px-3 py-2 text-left font-medium text-muted-foreground w-2/5">Header Name</th>
                                    <th className="px-3 py-2 text-left font-medium text-muted-foreground w-1/2">Header Value / Template</th>
                                    <th className="px-2 py-2 text-center font-medium text-muted-foreground w-[10%]">Action</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                  {s3CustomHeaders.map((header, idx) => (
                                    <tr key={idx} className="hover:bg-muted/20">
                                      <td className="p-2">
                                        <input
                                          type="text"
                                          value={header.key}
                                          onChange={(e) => handleHeaderChange(idx, 'key', e.target.value)}
                                          placeholder="e.g. x-emc-metadata-search"
                                          className="w-full bg-background border border-border rounded px-2 py-1 text-xs font-mono outline-none focus:ring-1 focus:ring-primary/50"
                                        />
                                      </td>
                                      <td className="p-2">
                                        <input
                                          type="text"
                                          value={header.value}
                                          onChange={(e) => handleHeaderChange(idx, 'value', e.target.value)}
                                          placeholder="e.g. Dept=${metadata.department}"
                                          className="w-full bg-background border border-border rounded px-2 py-1 text-xs font-mono outline-none focus:ring-1 focus:ring-primary/50"
                                        />
                                      </td>
                                      <td className="p-2 text-center">
                                        <button
                                          type="button"
                                          onClick={() => handleRemoveCustomHeader(header.key)}
                                          className="p-1 hover:text-red-400 text-muted-foreground transition-colors"
                                          title="Remove header"
                                        >
                                          <Trash2 className="h-3.5 w-3.5" />
                                        </button>
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          )}

                          {/* Add New Header Input Row */}
                          <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 pt-1">
                            <input
                              type="text"
                              value={newHeaderKey}
                              onChange={(e) => setNewHeaderKey(e.target.value)}
                              placeholder="New Header Name (e.g. x-emc-metadata-search)"
                              className="flex-1 bg-background border border-border rounded-md px-2.5 py-1.5 text-xs font-mono outline-none focus:ring-2 focus:ring-primary/50"
                            />
                            <input
                              type="text"
                              value={newHeaderValue}
                              onChange={(e) => setNewHeaderValue(e.target.value)}
                              placeholder="Header Value / Template (e.g. Department=${metadata.dept})"
                              className="flex-1 bg-background border border-border rounded-md px-2.5 py-1.5 text-xs font-mono outline-none focus:ring-2 focus:ring-primary/50"
                            />
                            <button
                              type="button"
                              onClick={handleAddCustomHeader}
                              disabled={!newHeaderKey.trim()}
                              className="inline-flex items-center justify-center gap-1.5 bg-primary/10 hover:bg-primary/20 text-primary border border-primary/20 rounded-md px-3 py-1.5 text-xs font-medium disabled:opacity-50 transition-colors"
                            >
                              <Plus className="h-3.5 w-3.5" />
                              Add Header
                            </button>
                          </div>

                          {/* Helper container explaining dynamic placeholders with high contrast chips */}
                          <div className="bg-card/70 border border-border/80 rounded-md p-2.5 space-y-1.5 mt-2">
                            <div className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                              <span className="text-orange-400 font-bold">Template Variables Reference:</span>
                            </div>
                            <p className="text-xs text-foreground/80 leading-relaxed">
                              Values are resolved dynamically per document. Use any of the following tokens in your header values:
                            </p>
                            <div className="flex flex-wrap gap-1.5 pt-1">
                              <code className="bg-orange-500/15 border border-orange-500/30 text-orange-400 font-mono text-xs px-2 py-0.5 rounded">
                                &#36;&#123;metadata.&lt;key&gt;&#125;
                              </code>
                              <code className="bg-orange-500/15 border border-orange-500/30 text-orange-400 font-mono text-xs px-2 py-0.5 rounded">
                                &#36;&#123;metadata.&lt;key&gt; | fallback&#125;
                              </code>
                              <code className="bg-orange-500/15 border border-orange-500/30 text-orange-400 font-mono text-xs px-2 py-0.5 rounded">
                                &#36;&#123;document.id&#125;
                              </code>
                              <code className="bg-orange-500/15 border border-orange-500/30 text-orange-400 font-mono text-xs px-2 py-0.5 rounded">
                                &#36;&#123;document.uri&#125;
                              </code>
                              <code className="bg-orange-500/15 border border-orange-500/30 text-orange-400 font-mono text-xs px-2 py-0.5 rounded">
                                &#36;&#123;document.action&#125;
                              </code>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Hyland OpenArch Output Connector */}
                  {selectedClass === 'org.opencrawling.openarch.output.OpenArchOutputConnector' && (
                    <div className="space-y-4 font-sans text-foreground">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-2 col-span-2">
                          <label className="text-sm font-medium">OpenArch Endpoint URL</label>
                          <input 
                            {...register('configuration.endpointUrl', { required: true })}
                            placeholder="http://localhost:8080"
                            defaultValue="http://localhost:8080"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Repository ID</label>
                          <input 
                            {...register('configuration.repositoryId')}
                            defaultValue="default"
                            placeholder="default"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Target Root Folder ID</label>
                          <input 
                            {...register('configuration.rootFolderId')}
                            defaultValue="00000000-0000-0000-0000-000000000000"
                            placeholder="00000000-0000-0000-0000-000000000000"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Username</label>
                          <input 
                            {...register('configuration.username')}
                            defaultValue="Administrator"
                            placeholder="Administrator"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Password</label>
                          <input 
                            type="password"
                            {...register('configuration.password')}
                            defaultValue="Administrator"
                            placeholder="Administrator"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Parquet Vector Batch Threshold</label>
                          <input 
                            type="number"
                            {...register('configuration.parquetBatchThreshold')}
                            defaultValue={50}
                            placeholder="50"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Embeddings Model Name / Type</label>
                          <input 
                            {...register('configuration.embeddingsType')}
                            defaultValue="mxbai-embed-large"
                            placeholder="mxbai-embed-large"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2">
                          <label className="text-sm font-medium">Embedding Vector Dimensions</label>
                          <input 
                            type="number"
                            {...register('configuration.dimensions')}
                            defaultValue={1024}
                            placeholder="1024"
                            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                          />
                        </div>

                        <div className="space-y-2 flex items-center pt-6">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.upsert')}
                              defaultChecked={true}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Upsert (Overwrite Existing Docs)
                          </label>
                        </div>

                        <div className="space-y-2 flex items-center pt-2">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.syncAcls')}
                              defaultChecked={true}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Zero-Trust ACL Synchronization
                          </label>
                        </div>

                        <div className="space-y-2 flex items-center pt-2">
                          <label className="flex items-center gap-2 cursor-pointer text-sm font-medium">
                            <input 
                              type="checkbox"
                              {...register('configuration.uploadBinaries')}
                              defaultChecked={true}
                              className="rounded border-border text-primary focus:ring-primary w-4 h-4 bg-background"
                            />
                            Upload Document Binaries (/api/upload)
                          </label>
                        </div>

                        <div className="col-span-2 p-3 bg-indigo-500/10 border border-indigo-500/20 rounded-md text-xs text-indigo-300 flex items-start gap-2">
                          <Layers className="w-4 h-4 flex-shrink-0 mt-0.5 text-indigo-400" />
                          <div>
                            <p className="font-semibold text-indigo-200">Parquet Vector Bridging &amp; OpenArch Indexing</p>
                            <p className="mt-0.5 opacity-90">
                              Emits binary embeddings formatted as Apache Parquet files conforming to OpenArch schema (<code className="text-indigo-200">embedding.avsc</code>) and indexes document objects into OpenArch with Zero-Trust permissions.
                            </p>
                          </div>
                        </div>

                        <div className="col-span-2 flex justify-end">
                          <button
                            type="button"
                            onClick={handleCheckConnection}
                            disabled={isCheckingConnection}
                            className="btn-secondary flex items-center gap-2 text-xs py-1.5 px-3 border border-indigo-500/30 text-indigo-300 hover:bg-indigo-500/10"
                          >
                            {isCheckingConnection ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />}
                            Test OpenArch Connectivity
                          </button>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Active Directory & LDAP */}
                  {(selectedClass === 'org.opencrawling.authorities.authorities.activedirectory.ActiveDirectoryAuthority' || 
                    selectedClass === 'org.opencrawling.authorities.authorities.ldap.LDAPAuthority') && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">LDAP server Domain Controller URL</label>
                        <input 
                          {...register('configuration.ldapUrl', { required: true })}
                          placeholder="ldap://dc.company.com:389"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Base DN</label>
                        <input 
                          {...register('configuration.baseDn', { required: true })}
                          placeholder="dc=company,dc=com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Bind Username</label>
                        <input 
                          {...register('configuration.bindUser')}
                          placeholder="cn=admin,dc=company,dc=com"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Bind Password</label>
                        <input 
                          type="password"
                          {...register('configuration.bindPassword')}
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                    </div>
                  )}

                  {/* Ollama Embedding */}
                  {selectedClass === 'org.opencrawling.embedding.OllamaEmbeddingConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Ollama Base URL</label>
                        <input 
                          {...register('configuration.baseUrl', { required: true })}
                          placeholder="http://localhost:11434"
                          defaultValue="http://localhost:11434"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Model Name</label>
                        <input 
                          {...register('configuration.model', { required: true })}
                          placeholder="e.g. mxbai-embed-large"
                          defaultValue="mxbai-embed-large"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <input type="hidden" {...register('configuration.engine')} value="ollama" />
                    </div>
                  )}

                  {/* OpenAI Embedding */}
                  {selectedClass === 'org.opencrawling.embedding.OpenAIEmbeddingConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">OpenAI API Key</label>
                        <input 
                          type="password"
                          {...register('configuration.apiKey', { required: true })}
                          placeholder="sk-..."
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Model Name</label>
                        <input 
                          {...register('configuration.model', { required: true })}
                          placeholder="e.g. text-embedding-3-small"
                          defaultValue="text-embedding-3-small"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                      </div>
                      <input type="hidden" {...register('configuration.engine')} value="openai" />
                    </div>
                  )}
                </div>
             )}
          </div>
        </form>
      </div>
    </div>
  )
}
