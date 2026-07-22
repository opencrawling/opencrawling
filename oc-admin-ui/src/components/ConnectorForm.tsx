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
  Network
} from 'lucide-react'
import { useState, useEffect } from 'react'
import { connectorApi } from '../lib/api'

type ConnectorType = 'repository' | 'output' | 'authority' | 'transformation'

interface ConnectorFormData {
  name: string
  description: string
  type: ConnectorType
  className: string
  maxConnections: number
  configuration: Record<string, string>
}

const getConnectorIconInfo = (className: string) => {
  if (className.includes('filesystem.FileConnector') || className.includes('FileSystem')) {
    return { icon: HardDrive, color: 'text-blue-400', bg: 'bg-blue-400/10', border: 'border-blue-500/20' }
  }
  if (className.includes('Alfresco')) {
    return { icon: Server, color: 'text-amber-400', bg: 'bg-amber-400/10', border: 'border-amber-500/20' }
  }
  if (className.includes('Iceberg')) {
    return { icon: Database, color: 'text-sky-400', bg: 'bg-sky-400/10', border: 'border-sky-500/20' }
  }
  if (className.includes('VectorOutputConnector') || className.includes('vector')) {
    return { icon: Network, color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-500/20' }
  }
  if (className.includes('Milvus')) {
    return { icon: Cpu, color: 'text-cyan-400', bg: 'bg-cyan-400/10', border: 'border-cyan-500/20' }
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

  const { register, handleSubmit, formState: { errors }, reset, setValue, watch } = useForm<ConnectorFormData>({
    defaultValues: {
      maxConnections: 10,
      type: 'repository',
      configuration: {}
    },
    shouldUnregister: true
  })

  const selectedClass = watch('className')
  const watchMaxConnections = watch('maxConnections') || 10

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
    reset(connector)
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
      { label: 'File System', value: 'org.opencrawling.crawler.connectors.filesystem.FileConnector' },
      { label: 'Alfresco Content Services Repository', value: 'org.opencrawling.alfresco.AlfrescoRepositoryConnector' },
      { label: 'Apache Iceberg Catalog Table', value: 'org.opencrawling.iceberg.IcebergRepositoryConnector' },
    ],
    transformation: [
      { label: 'Ollama Embedding', value: 'org.opencrawling.embedding.OllamaEmbeddingConnector' },
      { label: 'OpenAI Embedding', value: 'org.opencrawling.embedding.OpenAIEmbeddingConnector' }
    ],
    output: [
      { label: 'PGVector Store', value: 'org.opencrawling.vector.VectorOutputConnector' },
      { label: 'Milvus Vector Store', value: 'org.opencrawling.milvus.MilvusOutputConnector' },
      { label: 'OpenSearch 2.x Output Connector', value: 'org.opencrawling.opensearch2.OpenSearch2OutputConnector' },
      { label: 'OpenSearch 3.x Output Connector', value: 'org.opencrawling.opensearch3.OpenSearch3OutputConnector' },
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
                  <button type="button" onClick={handleReset} className="btn-secondary">
                    {selectedConnector ? 'Cancel' : 'Reset'}
                  </button>
                  <button type="submit" disabled={isSaving} className="btn-primary flex items-center gap-2 min-w-[120px] justify-center">
                    {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    {selectedConnector ? 'Update' : 'Save'}
                  </button>
               </div>
            </div>
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
                  {selectedClass === 'org.opencrawling.crawler.connectors.filesystem.FileConnector' && (
                    <div className="grid grid-cols-1 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Root Path / Scanning Directory</label>
                        <input 
                          {...register('configuration.rootPath', { required: true })}
                          placeholder="e.g. /Users/documents/scan"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
                        <p className="text-xs text-muted-foreground">The root folder on the local filesystem that this connector is authorized to scan.</p>
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
                      <div className="space-y-2 col-span-2">
                        <label className="text-sm font-medium">Batch Size</label>
                        <input 
                          type="number"
                          {...register('configuration.batchSize')}
                          defaultValue="100"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
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
                            <option value="HNSW">HNSW (Recommended)</option>
                            <option value="IVF_FLAT">IVF_FLAT</option>
                            <option value="FLAT">FLAT</option>
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

                  {/* Apache Solr */}
                  {selectedClass === 'org.opencrawling.agents.output.solr.SolrConnector' && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Solr Base URL</label>
                        <input 
                          {...register('configuration.solrUrl', { required: true })}
                          placeholder="http://localhost:8983/solr"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Collection / Core</label>
                        <input 
                          {...register('configuration.solrCore', { required: true })}
                          placeholder="opencrawling"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
                        />
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
