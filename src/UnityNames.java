import java.util.List;
import java.util.Set;

/** Names that Unity or .NET call by reflection / convention and must therefore never be renamed. */
public final class UnityNames {
    private UnityNames() {}

    /** MonoBehaviour / ScriptableObject / Editor messages invoked by Unity by name. */
    public static final Set<String> MESSAGES = Set.copyOf(List.of(
            "Awake", "Start", "Update", "FixedUpdate", "LateUpdate", "OnEnable", "OnDisable", "OnDestroy",
            "OnGUI", "OnValidate", "Reset", "OnApplicationFocus", "OnApplicationPause", "OnApplicationQuit",
            "OnBecameVisible", "OnBecameInvisible", "OnCollisionEnter", "OnCollisionExit", "OnCollisionStay",
            "OnCollisionEnter2D", "OnCollisionExit2D", "OnCollisionStay2D", "OnTriggerEnter", "OnTriggerExit",
            "OnTriggerStay", "OnTriggerEnter2D", "OnTriggerExit2D", "OnTriggerStay2D", "OnMouseDown", "OnMouseUp",
            "OnMouseDrag", "OnMouseEnter", "OnMouseExit", "OnMouseOver", "OnMouseUpAsButton", "OnDrawGizmos",
            "OnDrawGizmosSelected", "OnPreCull", "OnPreRender", "OnPostRender", "OnRenderImage", "OnRenderObject",
            "OnWillRenderObject", "OnAnimatorIK", "OnAnimatorMove", "OnAudioFilterRead", "OnParticleCollision",
            "OnParticleTrigger", "OnParticleSystemStopped", "OnParticleUpdateJobScheduled", "OnJointBreak",
            "OnJointBreak2D", "OnLevelWasLoaded", "OnTransformChildrenChanged", "OnTransformParentChanged",
            "OnBeforeTransformParentChanged", "OnRectTransformDimensionsChange", "OnRectTransformRemoved",
            "OnCanvasGroupChanged", "OnCanvasHierarchyChanged", "OnControllerColliderHit", "OnDidApplyAnimationProperties",
            "OnConnectedToServer", "OnDisconnectedFromServer", "OnFailedToConnect", "OnMasterServerEvent",
            "OnNetworkInstantiate", "OnPlayerConnected", "OnPlayerDisconnected", "OnSerializeNetworkView",
            "OnServerInitialized", "OnBeforeSerialize", "OnAfterDeserialize", "OnSceneGUI", "OnInspectorGUI",
            "OnHeaderGUI", "OnPreviewGUI", "OnInteractivePreviewGUI", "OnPreviewSettings", "OnEnable", "OnDisable",
            "OnDestroy", "OnPointerDown", "OnPointerUp", "OnPointerClick", "OnPointerEnter", "OnPointerExit",
            "OnPointerMove", "OnDrag", "OnBeginDrag", "OnEndDrag", "OnDrop", "OnScroll", "OnSelect", "OnDeselect",
            "OnMove", "OnSubmit", "OnCancel", "OnUpdateSelected", "OnInitializePotentialDrag", "OnPopulateMesh",
            "OnStateEnter", "OnStateExit", "OnStateUpdate", "OnStateMove", "OnStateIK", "OnStateMachineEnter",
            "OnStateMachineExit", "OnAnimatorStateEnter", "OnDidApplyAnimationProperties", "OnCullingGroupChanged",
            "OnAudioRead", "OnAudioSetPosition", "OnProcessScene", "OnPreprocessBuild", "OnPostprocessBuild",
            "OnPostprocessAllAssets", "OnPreprocessTexture", "OnPreprocessModel", "OnPostprocessModel",
            "OnPreprocessAudio", "OnPostprocessAudio", "OnPreprocessAsset", "OnOpenAsset", "OnPlayModeStateChanged",
            "OnHierarchyChange", "OnProjectChange", "OnSelectionChange", "OnFocus", "OnLostFocus", "OnDestroy",
            "OnBeforeSceneLoadRuntimeMethod", "OnRuntimeMethodLoad", "OnWizardCreate", "OnWizardUpdate",
            "OnWizardOtherButton", "OnInspectorUpdate", "OnAddedAsTab", "OnRemovedAsTab", "OnTabDetached",
            "OnMainWindowMove", "ShowButton", "CreateGUI", "OnGUI", "OnBecameVisible", "OnBecameInvisible",
            "OnNetworkSpawn", "OnNetworkDespawn", "OnGainedOwnership", "OnLostOwnership", "OnGraphStart", "OnGraphStop",
            "OnPlayableCreate", "OnPlayableDestroy", "OnBehaviourPlay", "OnBehaviourPause", "PrepareFrame",
            "ProcessFrame", "OnBehaviourDelay", "OnBehaviourStart", "OnBehaviourStop", "OnOpenAsset",
            "Main", "OnEnterPlayMode", "OnExitPlayMode", "OnRenderShadowMap", "OnSceneChanged"));

    /** Methods every object has; a project method with the same name is likely an override/hiding. */
    public static final Set<String> OBJECT_METHODS = Set.copyOf(List.of(
            "ToString", "Equals", "GetHashCode", "GetType", "CompareTo", "Dispose", "DisposeAsync", "GetEnumerator",
            "MoveNext", "Current", "Reset", "Finalize", "Clone", "MemberwiseClone", "Invoke", "BeginInvoke",
            "EndInvoke", "GetObjectData", "Deconstruct", "GetAwaiter", "GetResult", "IsCompleted", "OnCompleted",
            "UnsafeOnCompleted", "Add", "Remove", "Contains", "Count", "Length", "Clear", "CopyTo", "IsReadOnly",
            "Item", "IndexOf", "Insert", "RemoveAt", "Keys", "Values", "TryGetValue", "ContainsKey", "Read", "Write",
            "Serialize", "Deserialize", "GetSchema", "ReadXml", "WriteXml", "PropertyChanged", "CollectionChanged",
            "ErrorsChanged", "HasErrors", "GetErrors", "Value", "HasValue", "Comparer", "Compare", "GetFormat",
            "Format", "Parse", "TryParse", "TryFormat"));

    /** Attributes whose presence means the member is looked up by name at runtime. */
    public static final Set<String> KEEP_ATTRIBUTES = Set.copyOf(List.of(
            "Preserve", "DllImport", "MonoPInvokeCallback", "AOT.MonoPInvokeCallback", "ContextMenuItem",
            "UnityEngine.Scripting.Preserve", "SerializeField", "SerializeReference", "InspectorName",
            "RecordPositional", "DelegateInvoke", "Button", "ShowInInspector"));

    /** Attributes that mark a type as serializable by Unity (its public fields must keep their names). */
    public static final Set<String> SERIALIZABLE_TYPE_ATTRIBUTES = Set.of("Serializable", "System.Serializable");
}
