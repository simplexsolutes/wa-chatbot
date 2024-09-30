package zw.co.dcl.jawce.engine.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zw.co.dcl.jawce.engine.constants.EngineConstants;
import zw.co.dcl.jawce.engine.constants.SessionConstants;
import zw.co.dcl.jawce.engine.constants.TemplateTypes;
import zw.co.dcl.jawce.engine.model.dto.EnginePreProcessor;
import zw.co.dcl.jawce.engine.model.dto.MessageDto;
import zw.co.dcl.jawce.engine.model.dto.MsgProcessorDTO;
import zw.co.dcl.jawce.engine.model.dto.MsgProcessorResponseDTO;
import zw.co.dcl.jawce.engine.exceptions.EngineInternalException;
import zw.co.dcl.jawce.engine.exceptions.EngineResponseException;
import zw.co.dcl.jawce.engine.exceptions.EngineSessionExpiredException;
import zw.co.dcl.jawce.engine.exceptions.EngineSessionInactivityException;
import zw.co.dcl.jawce.engine.processor.abstracts.ChannelMessageProcessor;
import zw.co.dcl.jawce.engine.processor.iface.IMessageProcessor;
import zw.co.dcl.jawce.engine.service.EngineRequestService;
import zw.co.dcl.jawce.engine.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MessageProcessor extends ChannelMessageProcessor implements IMessageProcessor {
    private final Logger logger = LoggerFactory.getLogger(MessageProcessor.class);
    List<EnginePreProcessor> nestedPreProcessorResults;

    public MessageProcessor(MsgProcessorDTO messageProcessorDTO, EngineRequestService engineService) {
        super(messageProcessorDTO, engineService);
    }

    @Override
    public String getNextRoute() {
        if(this.isFirstTime) return dto.sessionSettings().getStartMenuStageKey();
        if(hasInteractionActivityExpired()) {
            throw new EngineSessionInactivityException("You have been inactive for a while, to secure your account, kindly login again");
        }

//        TODO: if session expired while outside - reroute to general start menu

        String currentStage = (String) this.session.get(this.sessionId, SessionConstants.CURRENT_STAGE);
        var currentStageRoutes = (Map<String, Object>) this.currentStageTpl.get(EngineConstants.TPL_ROUTES_KEY);
        var checkpoint = this.session.get(this.sessionId, SessionConstants.SESSION_LATEST_CHECKPOINT_KEY);

        boolean gotoCheckpoint = !this.templateHasKey(currentStageRoutes, EngineConstants.RETRY_NAME)
                && this.currentStageUserInput.toString().equalsIgnoreCase(EngineConstants.RETRY_NAME)
                && checkpoint != null
                && this.session.get(this.sessionId, SessionConstants.SESSION_DYNAMIC_RETRY_KEY) != null
                && !this.isFromTrigger;

        if(gotoCheckpoint) return checkpoint.toString();

        var hasDynamicRoute = dynamicRouter();
        if(hasDynamicRoute != null) return hasDynamicRoute;

        logger.info("[{}] Current - stage: [{}], template: {}",
                this.sessionId,
                currentStage,
                this.currentStageTpl
        );

        for (Map.Entry<String, Object> route : currentStageRoutes.entrySet()) {
            if(CommonUtils.isRegexInput(route.getKey().trim())) {
                if(CommonUtils.isRegexPatternMatch(
                        CommonUtils.getRegexPattern(route.getKey().trim()),
                        this.currentStageUserInput.input().trim()
                )) {
                    return route.getValue().toString().trim();
                }
            }
        }

        if(this.templateHasKey(currentStageRoutes, this.currentStageUserInput.input().trim()))
            return currentStageRoutes.get(this.currentStageUserInput.input().trim()).toString();

        throw new EngineResponseException(CommonUtils.createEngineErrorMsg(
                currentStage,
                this.sessionId,
                "Invalid response, please try again"
        ));
    }

    @Override
    public boolean hasInteractionActivityExpired() {
        if(!this.dto.sessionSettings().isHandleSessionInactivity()) return false;

        var authKey = this.session.get(this.sessionId, SessionConstants.ENGINE_AUTH_VALID_KEY, Boolean.class);
        var lastActive = this.session.get(this.sessionId, SessionConstants.LAST_ACTIVITY_KEY, String.class);

        if(authKey != null) {
            return CommonUtils.hasInteractionExpired(lastActive, this.dto.sessionSettings().getInactivityTimeout());
        }

        return false;
    }

    @Override
    public Map<String, Object> authenticate(Map<String, Object> template) {
        if(this.templateHasKey(template, EngineConstants.TPL_AUTHENTICATED_KEY)) {
            var hasAuth = this.session.get(this.sessionId, SessionConstants.ENGINE_AUTH_VALID_KEY);
            String loggedInTime = this.session.get(this.sessionId, SessionConstants.SESSION_EXPIRY, String.class);
            String sessionUid = this.session.get(this.sessionId, SessionConstants.SERVICE_PROFILE_MSISDN_KEY, String.class);

            if(loggedInTime == null || sessionUid == null || hasAuth == null || CommonUtils.hasSessionExpired(loggedInTime)) {
                throw new EngineSessionExpiredException("Your session has expired. Kindly login again to access our WhatsApp Services");
            }
        }

        return template;
    }

    @Override
    public EnginePreProcessor preProcessor() throws Exception {
        if(this.session.get(this.sessionId, SessionConstants.SESSION_DYNAMIC_RETRY_KEY) == null)
            this.processPostHooks();

        Map<String, Object> nTemplate;
        String nStage;

        if(hasEngineFullDynamicTemplateBody(SessionConstants.DYNAMIC_NEXT_TEMPLATE_BODY_KEY)) {
            nStage = EngineConstants.DYNAMIC_BODY_STAGE_KEY;
            nTemplate = this.session.get(this.sessionId, SessionConstants.DYNAMIC_NEXT_TEMPLATE_BODY_KEY, Map.class);
        } else {
            nStage = this.getNextRoute();

            if(!this.templateHasKey(this.dto.tplContextMap(), nStage))
                throw new EngineInternalException("route: " + nStage + " not found");

            nTemplate = (Map) this.dto.tplContextMap().get(nStage);

            if(this.templateHasKey(nTemplate, EngineConstants.TPL_DYNAMIC_ROUTER_KEY)
                    && this.templateHasKey(nTemplate, EngineConstants.TPL_ROUTE_TRANSIENT_KEY)
            ) {
                this.currentStageTpl = nTemplate;
                logger.warn("[{}] Found transient flow dynamic router -> {}, re-processing..", this.sessionId, nStage);
                nestedPreProcessorResults.add(this.preProcessor());
            }

            nTemplate = this.authenticate(nTemplate);
        }

        if(!nestedPreProcessorResults.isEmpty()) {
            var latest = nestedPreProcessorResults.get(0);
            nStage = latest.stage();
            nTemplate = latest.template();
        }

        logger.info("[{}] Next stage: {} ", this.sessionId, nStage);
        return new EnginePreProcessor(nStage, nTemplate);
    }

    @Override
    public MsgProcessorResponseDTO process() throws Exception {
        nestedPreProcessorResults = new ArrayList<>();
        EnginePreProcessor preResult = this.preProcessor();
        Map<String, Object> nextStageTpl = preResult.template();
        String nextStage = preResult.stage();

        this.processPreHooks(nextStageTpl);

        final MessageDto messageDto = new MessageDto(
                engineService,
                nextStageTpl,
                hookArgs,
                (String) this.session.get(this.sessionId, SessionConstants.CURRENT_STAGE),
                CommonUtils.getPreviousMessageId(nextStageTpl)
        );

        Map<String, Object> payload = switch (nextStageTpl.get("type").toString()) {
            case TemplateTypes.DYNAMIC -> {
                var dText = new DynamicMessage(messageDto);
                yield dText.generatePayload();
            }
            case TemplateTypes.TEXT -> {
                var gText = new GeneralText(messageDto);
                yield gText.generatePayload();
            }
            case TemplateTypes.BUTTON -> {
                var btn = new ButtonMessage(messageDto);
                yield btn.generatePayload();
            }
            case TemplateTypes.LIST -> {
                var intrMessage = new InteractiveListMessage(messageDto);
                yield intrMessage.generatePayload();
            }
            case TemplateTypes.FLOW -> {
                var flowMessage = new FlowMessage(messageDto);
                yield flowMessage.generatePayload();
            }
            case TemplateTypes.MEDIA, TemplateTypes.DOCUMENT, TemplateTypes.IMAGE -> {
                var docMsg = new MediaMessage(messageDto);
                yield docMsg.generatePayload();
            }
            case TemplateTypes.TEMPLATE -> {
                var tText = new TemplateMessage(messageDto);
                yield tText.generatePayload();
            }
            case TemplateTypes.LOCATION -> {
                var lText = new LocationMessage(messageDto);
                yield lText.generatePayload();
            }
            case TemplateTypes.REQUEST_LOCATION -> {
                var lrText = new LocationRequestMessage(messageDto);
                yield lrText.generatePayload();
            }
            default ->
                    throw new EngineInternalException("specified template type not supported for stage: " + nextStage);
        };

        this.setFromTrigger(false);
        this.session.evict(this.sessionId, SessionConstants.SESSION_DYNAMIC_RETRY_KEY);
        return new MsgProcessorResponseDTO(payload, nextStage, dto.waCurrentUser().waId());
    }
}