package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.RedeemInviteCodeRequest;
import com.cloudmind.demo.service.InviteCodeService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/invites")
public class InviteCodeController {
    private final InviteCodeService inviteCodeService;

    public InviteCodeController(InviteCodeService inviteCodeService) {
        this.inviteCodeService = inviteCodeService;
    }

    @PostMapping("/redeem")
    public Map<String, Object> redeem(
            @RequestHeader(value = "X-Token", required = false) String token,
            @Valid @RequestBody RedeemInviteCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        return Map.of(
                "success", true,
                "message", "会员兑换成功",
                "data", inviteCodeService.redeem(
                        token,
                        request.getCode(),
                        servletRequest.getRemoteAddr(),
                        servletRequest.getHeader("User-Agent")
                )
        );
    }
}
