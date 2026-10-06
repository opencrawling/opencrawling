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
  X, 
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
  Archive
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
  if (className.includes('StormCrawler') || className.includes('stormcrawler')) {
    return { icon: Globe, color: 'text-sky-400', bg: 'bg-sky-400/10', border: 'border-sky-500/20' }
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
  return { icon: Plug2, color: 'text-muted-foreground', bg: 'bg-muted-foreground/10', border: 'border-muted-foreground/20' }
}

export default function ConnectorForm() {
  const [activeTab, setActiveTab] = useState<ConnectorType>('repository')
  const [connectors, setConnectors] = useState<ConnectorFormData[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [selectedConnector, setSelectedConnector] = useState<string | null>(null)

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
    reset(connector)
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
      { label: 'Relational Database (JDBC)', value: 'org.opencrawling.jdbc.JdbcRepositoryConnector' },
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
