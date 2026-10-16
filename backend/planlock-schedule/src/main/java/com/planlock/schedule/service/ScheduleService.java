package com.planlock.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.schedule.ScheduleRules;
import com.planlock.schedule.dto.ScheduleResponse;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.schedule.entity.Schedule;
import com.planlock.schedule.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor
public class ScheduleService {
    private final ScheduleMapper mapper;
    private final ObjectMapper objectMapper;
    public List<ScheduleResponse> list(Long userId){ return mapper.findByUserId(userId).stream().map(this::toResponse).toList(); }
    @Transactional public ScheduleResponse create(Long userId, ScheduleUpsertRequest req){
        ScheduleRules.validate(req, objectMapper); validateClientId(req.getClientId()); Schedule s=new Schedule(); s.setUserId(userId); s.setClientId(req.getClientId()); apply(s,req); s.setStatus(1); s.setVersion(0L); mapper.insert(s); return toResponse(mapper.findByIdForUpdate(s.getId()));
    }
    @Transactional public ScheduleResponse update(Long userId,Long id,Long baseVersion,ScheduleUpsertRequest req){
        if(baseVersion==null) throw new BusinessException(ErrorCode.INVALID_PARAM,"缺少 baseVersion");
        Schedule old=mapper.findById(id); if(old==null||!old.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NOT_FOUND,"日程不存在");
        ScheduleRules.validate(req,objectMapper); Schedule s=new Schedule(); s.setId(id); s.setUserId(userId); s.setVersion(baseVersion); apply(s,req);
        if(mapper.updateWithVersion(s)==0) throw new BusinessException(ErrorCode.VERSION_CONFLICT); return toResponse(mapper.findById(id));
    }
    @Transactional public void delete(Long userId,Long id){ delete(userId,id,null); }
    @Transactional public void delete(Long userId,Long id,Long baseVersion){
        Schedule old=mapper.findById(id);
        if(old==null)return;
        if(!old.getUserId().equals(userId))throw new BusinessException(ErrorCode.NOT_FOUND,"日程不存在");
        if(baseVersion==null){
            mapper.delete(id,userId);
        }else if(mapper.deleteWithVersion(id,userId,baseVersion)==0){
            // Current locking read preserves idempotency when another transaction deleted the row.
            Schedule current=mapper.findByIdForUpdate(id);
            if(current==null)return;
            if(!current.getUserId().equals(userId))throw new BusinessException(ErrorCode.NOT_FOUND,"日程不存在");
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }
    private void validateClientId(String clientId){ if(clientId!=null&&(clientId.isBlank()||clientId.length()>64))throw new BusinessException(ErrorCode.INVALID_PARAM,"clientId 长度须为 1 至 64"); }
    private void apply(Schedule s,ScheduleUpsertRequest r){ s.setTitle(r.getTitle().trim());s.setStartDate(r.getStartDate());s.setStartTime(r.getStartTime());s.setEndDate(r.getEndDate());s.setEndTime(r.getEndTime());s.setAllDay(r.getAllDay());s.setLocation(r.getLocation());s.setNote(r.getNote());s.setRemindAdvanceMin(r.getRemindAdvanceMin());s.setRepeatRule(r.getRepeatRule());s.setLinkedPlanId(r.getLinkedPlanId());s.setTzId(r.getTzId()); }
    private ScheduleResponse toResponse(Schedule s){return ScheduleResponse.builder().id(s.getId()).clientId(s.getClientId()).userId(s.getUserId()).title(s.getTitle()).startDate(s.getStartDate()).startTime(s.getStartTime()).endDate(s.getEndDate()).endTime(s.getEndTime()).allDay(s.getAllDay()).location(s.getLocation()).note(s.getNote()).remindAdvanceMin(s.getRemindAdvanceMin()).repeatRule(s.getRepeatRule()).linkedPlanId(s.getLinkedPlanId()).tzId(s.getTzId()).status(s.getStatus()).version(s.getVersion()).createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt()).build();}
}
