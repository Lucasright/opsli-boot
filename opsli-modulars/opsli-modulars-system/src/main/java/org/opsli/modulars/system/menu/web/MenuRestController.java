/**
 * Copyright 2020 OPSLI 快速开发平台 https://www.opsli.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opsli.modulars.system.menu.web;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeNodeConfig;
import cn.hutool.core.util.ReflectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.opsli.api.base.result.ResultVo;
import org.opsli.api.web.system.menu.MenuApi;
import org.opsli.api.wrapper.system.menu.MenuFullModel;
import org.opsli.api.wrapper.system.menu.MenuModel;
import org.opsli.api.wrapper.system.user.UserModel;
import org.opsli.common.annotation.ApiRestController;
import org.opsli.common.annotation.EnableLog;
import org.opsli.common.annotation.RequiresPermissionsCus;
import org.opsli.common.constants.MenuConstants;
import org.opsli.common.constants.MyBatisConstants;
import org.opsli.common.enums.DictType;
import org.opsli.common.utils.FieldUtil;
import org.opsli.common.utils.WrapperUtil;
import org.opsli.core.base.controller.BaseRestController;
import org.opsli.core.general.StartPrint;
import org.opsli.core.persistence.Page;
import org.opsli.core.persistence.querybuilder.GenQueryBuilder;
import org.opsli.core.persistence.querybuilder.QueryBuilder;
import org.opsli.core.persistence.querybuilder.WebQueryBuilder;
import org.opsli.core.utils.TreeBuildUtil;
import org.opsli.core.utils.UserUtil;
import org.opsli.modulars.system.menu.entity.SysMenu;
import org.opsli.modulars.system.menu.service.IMenuService;
import org.opsli.modulars.system.user.service.IUserRoleRefService;
import org.opsli.modulars.system.user.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;


/**
 * 菜单管理 Controller
 *
 * @author Parker
 * @date 2020-09-16 17:33
 */
@Api(tags = MenuApi.TITLE)
@Slf4j
@ApiRestController("/system/menu/{ver}")
public class MenuRestController extends BaseRestController<SysMenu, MenuModel, IMenuService>
        implements MenuApi {

    @Autowired
    private IUserRoleRefService iUserRoleRefService;

    /** 排序字段 */
    private static final String SORT_FIELD = "order";

    /** 虚拟总节点 ID */
    private static final String VIRTUAL_TOTAL_NODE = "-1";

    /** 菜单排除字段 */
    private static final String[] EXCLUSION_FIELDS = {
            "createBy", "createTime", "updateBy", "updateTime",
            "deleted", "permissions", "menuName", "ts", "encryptData",
            "hidden", "version", "sortNo", "url", "icon"
    };

    /**
     * 根据 获得用户 菜单 - 按钮权限 不是高频率
     * 判断是否是超级管理员，如果是 则显示全部菜单 否则显示有权限菜单
     *
     * @return ResultVo
     */
    @ApiOperation(value = "根据 获得用户 菜单 - 权限", notes = "根据 获得用户 菜单 - 权限")
    @Override
    public ResultVo<?> getMenuAndPermsTree(String label) {
        UserModel user = UserUtil.getUser();

        // 获得当前用户菜单
        List<MenuModel> menuModelList = iUserRoleRefService.getMenuAllListByUserId(user.getId(), label);

        // 这里有坑 如果 为 菜单数据 且 组件(Component)地址为空 不会跳转到主页 也不报错
        // 修复菜单问题导致无法跳转主页
        menuModelList.removeIf(
                menuModel -> MenuConstants.MENU.equals(menuModel.getType()) &&
                (StringUtils.isEmpty(menuModel.getComponent()) ||
                        StringUtils.isEmpty(menuModel.getUrl())
                ));

        // 获得菜单树
        List<Tree<Object>> treeNodes = getMenuTrees(menuModelList);

        return ResultVo.success(treeNodes);
    }

    /**
     * 当前登陆用户菜单 高频率
     *
     * 判断是否是超级管理员，如果是 则显示全部菜单 否则显示有权限菜单
     *
     * @return ResultVo
     */
    @ApiOperation(value = "当前登陆用户菜单", notes = "当前登陆用户菜单")
    @Override
    public ResultVo<?> findMenuTree() {
        UserModel user = UserUtil.getUser();

        // 获得用户 对应菜单
        List<MenuModel> menuModelList = UserUtil.getMenuListByUserId(user.getId());

        // 这里有坑 如果 为 菜单数据 且 组件(Component)地址为空 不会跳转到主页 也不报错
        // 修复菜单问题导致无法跳转主页
        menuModelList.removeIf(
                menuModel -> MenuConstants.MENU.equals(menuModel.getType()) &&
                (StringUtils.isEmpty(menuModel.getComponent()) ||
                StringUtils.isEmpty(menuModel.getUrl())
                        ));

        // 获得菜单树
        List<Tree<Object>> treeNodes = getMenuTrees(menuModelList, EXCLUSION_FIELDS);

        return ResultVo.success(treeNodes);
    }


    /**
     * 懒加载菜单
     * @param parentId 父节点ID
     * @param id 自身ID （不为空 则排除自身）
     * @return ResultVo
     */
    @ApiOperation(value = "获得菜单树 懒加载", notes = "获得菜单树 懒加载")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<?> findMenuTreeByLazy(String parentId, String id) {
        List<MenuModel> menuModelList;
        if(StringUtils.isEmpty(parentId)){
            menuModelList = Lists.newArrayList();
            // 生成根节点菜单
            MenuModel model = getGenMenuModel();
            parentId = model.getParentId();
            menuModelList.add(model);
        }else{
            // 只查菜单
            QueryBuilder<SysMenu> queryBuilder = new GenQueryBuilder<>();
            QueryWrapper<SysMenu> queryWrapper = queryBuilder.build();
            queryWrapper.eq(
                    FieldUtil.humpToUnderline(MyBatisConstants.FIELD_PARENT_ID), parentId);
            queryWrapper.eq("type", MenuConstants.MENU);

            // 如果传入ID 则不包含自身
            if(StringUtils.isNotEmpty(id)){
                queryWrapper.notIn(
                        FieldUtil.humpToUnderline(MyBatisConstants.FIELD_ID), id);

            }

            // 获得菜单
            List<SysMenu> menuList = IService.findList(queryWrapper);
            menuModelList = WrapperUtil.transformInstance(menuList, MenuModel.class);
        }

        // 获得菜单树
        List<Tree<Object>> treeNodes = getMenuTrees(menuModelList, parentId,1);

        // 处理是否包含子集
        super.handleTreeHasChildren(treeNodes,
                (parentIds)-> IService.hasChildrenByChoose(parentIds));

        return ResultVo.success(treeNodes);
    }

    /**
     * 获得列表菜单树 懒加载
     *
     * @param label 标签
     * @param parentId 父节点ID
     * @return ResultVo
     */
    @ApiOperation(value = "获得列表菜单树 懒加载", notes = "获得列表菜单树 懒加载")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<?> findMenuTreePageByLazy(String parentId) {
        List<MenuModel> menuModelList;
        if(StringUtils.isEmpty(parentId)){
            menuModelList = Lists.newArrayList();
            // 生成根节点菜单
            MenuModel model = getGenMenuModel();
            parentId = model.getParentId();
            menuModelList.add(model);
        }else{
            QueryBuilder<SysMenu> queryBuilder = new GenQueryBuilder<>();
            QueryWrapper<SysMenu> queryWrapper = queryBuilder.build();
            queryWrapper.eq(FieldUtil.humpToUnderline(MyBatisConstants.FIELD_PARENT_ID), parentId);

            // 获得菜单
            List<SysMenu> menuList = IService.findList(queryWrapper);
            menuModelList = WrapperUtil.transformInstance(menuList, MenuModel.class);
        }
        // 获得菜单树
        List<Tree<Object>> treeNodes = getMenuTrees(menuModelList, parentId,1);

        // 处理是否包含子集
        super.handleTreeHasChildren(treeNodes,
                (parentIds)-> IService.hasChildren(parentIds));

        return ResultVo.success(treeNodes);
    }

    /**
     * 获得列表菜单树
     * @return ResultVo
     */
    @ApiOperation(value = "获得列表菜单树", notes = "获得列表菜单树")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<?> findMenuTreePage(HttpServletRequest request) {
        QueryBuilder<SysMenu> queryBuilder = new WebQueryBuilder<>(entityClazz,
                request.getParameterMap());

        // 获得菜单
        List<SysMenu> menuList = IService.findList(queryBuilder.build());
        List<MenuModel> menuModelList = WrapperUtil.transformInstance(menuList, MenuModel.class);

        // 获得菜单树
        List<Tree<Object>> treeNodes = getMenuTrees(menuModelList);

        return ResultVo.success(treeNodes);
    }

    /**
     * 获得菜单List
     * @return ResultVo
     */
    @ApiOperation(value = "获得菜单List", notes = "获得菜单List")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<List<MenuModel>> findList() {
        QueryBuilder<SysMenu> queryBuilder = new GenQueryBuilder<>();
        // 菜单集合
        List<SysMenu> menuList = IService.findList(queryBuilder.build());
        List<MenuModel> menuModelList = WrapperUtil.transformInstance(menuList, modelClazz);
        return ResultVo.success(menuModelList);
    }

    /**
     * 菜单 查一条
     * @param model 模型
     * @return ResultVo
     */
    @ApiOperation(value = "获得单条菜单", notes = "获得单条菜单 - ID")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<MenuModel> get(MenuModel model) {
        if(model != null){
            if(StringUtils.equals(MenuConstants.GEN_ID, model.getId())){
                // 生成根节点菜单
                model = getGenMenuModel();
            }else{
                // 如果系统内部调用 则直接查数据库
                if (model.getIzApi() != null && model.getIzApi()){
                    model = IService.get(model);
                }
            }
        }

        return ResultVo.success(model);
    }

    /**
     * 菜单 查询分页
     * @param pageNo 当前页
     * @param pageSize 每页条数
     * @param request request
     * @return ResultVo
     */
    @ApiOperation(value = "获得分页数据", notes = "获得分页数据 - 查询构造器")
    @RequiresPermissions("system_menu_select")
    @Override
    public ResultVo<?> findPage(Integer pageNo, Integer pageSize, HttpServletRequest request) {

        QueryBuilder<SysMenu> queryBuilder = new WebQueryBuilder<>(entityClazz, request.getParameterMap());
        Page<SysMenu, MenuModel> page = new Page<>(pageNo, pageSize);
        page.setQueryWrapper(queryBuilder.build());
        page = IService.findPage(page);

        return ResultVo.success(page.getPageData());
    }

    /**
     * 菜单 新增
     * @param model 模型
     * @return ResultVo
     */
    @ApiOperation(value = "新增菜单", notes = "新增菜单")
    @RequiresPermissions("system_menu_insert")
    @EnableLog
    @Override
    public ResultVo<?> insert(MenuModel model) {
        // 演示模式 不允许操作
        super.demoError();

        // 调用新增方法
        IService.insert(model);
        return ResultVo.success("新增菜单成功");
    }

    /**
     * 菜单 修改
     * @param model 模型
     * @return ResultVo
     */
    @ApiOperation(value = "修改菜单", notes = "修改菜单")
    @RequiresPermissions("system_menu_update")
    @EnableLog
    @Override
    public ResultVo<?> update(MenuModel model) {
        // 演示模式 不允许操作
        super.demoError();

        // 调用修改方法
        IService.update(model);
        return ResultVo.success("修改菜单成功");
    }


    /**
     * 菜单 删除
     * @param id ID
     * @return ResultVo
     */
    @ApiOperation(value = "删除菜单数据", notes = "删除菜单数据")
    @RequiresPermissions("system_menu_delete")
    @EnableLog
    @Override
    public ResultVo<?> del(String id){
        // 演示模式 不允许操作
        super.demoError();

        IService.delete(id);
        return ResultVo.success("删除菜单成功");
    }


    /**
     * 菜单 批量删除
     * @param ids ID 数组
     * @return ResultVo
     */
    @ApiOperation(value = "批量删除菜单数据", notes = "批量删除菜单数据")
    @RequiresPermissions("system_menu_delete")
    @EnableLog
    @Override
    public ResultVo<?> delAll(String ids){
        // 演示模式 不允许操作
        super.demoError();

        String[] idArray = Convert.toStrArray(ids);
        IService.deleteAll(idArray);

        return ResultVo.success("批量删除菜单成功");
    }


    /**
     * 菜单 Excel 导出
     * @param request request
     * @param response response
     */
    @ApiOperation(value = "导出Excel", notes = "导出Excel")
    @RequiresPermissionsCus("system_menu_export")
    @EnableLog
    @Override
    public void exportExcel(HttpServletRequest request, HttpServletResponse response) {
        // 当前方法
        Method method = ReflectUtil.getMethodByName(this.getClass(), "exportExcel");
        QueryBuilder<SysMenu> queryBuilder = new WebQueryBuilder<>(entityClazz, request.getParameterMap());
        super.excelExport(MenuApi.SUB_TITLE, queryBuilder.build(), response, method);
    }

    /**
     * 菜单 Excel 导入
     * @param request 文件流 request
     * @return ResultVo
     */
    @ApiOperation(value = "导入Excel", notes = "导入Excel")
    @RequiresPermissions("system_menu_import")
    @EnableLog
    @Override
    public ResultVo<?> importExcel(MultipartHttpServletRequest request) {
        return super.importExcel(request);
    }

    /**
     * 菜单 Excel 下载导入模版
     * @param response response
     */
    @ApiOperation(value = "导出Excel模版", notes = "导出Excel模版")
    @RequiresPermissionsCus("system_menu_import")
    @EnableLog
    @Override
    public void importTemplate(HttpServletResponse response) {
        // 当前方法
        Method method = ReflectUtil.getMethodByName(this.getClass(), "importTemplate");
        super.importTemplate(MenuApi.SUB_TITLE, response, method);
    }


    // ==================

    /**
     * 根据菜单权限 获得菜单
     * @param permissions 菜单权限
     * @return ResultVo
     */
    @Override
    public ResultVo<MenuModel> getByPermissions(String permissions) {
        MenuModel menu = IService.getByPermissions(permissions);
        if(menu == null){
            ResultVo.error("暂无数据");
        }
        return ResultVo.success(menu);
    }


    // ==============================

    /**
     * 获得BeanMap集合
     * @param dataList 数据集合
     * @return List
     */
    private List<Map<String, Object>> getBeanMapList(List<MenuModel> dataList, String[] exclusionFields) {
        List<Map<String, Object>> beanMapList = Lists.newArrayList();
        if(CollUtil.isEmpty(dataList)){
            return beanMapList;
        }

        // 转化为 BeanMap 处理数据
        for (MenuModel model : dataList) {
            Map<String, Object> beanToMap = BeanUtil.beanToMap(model);
            // 排除字段
            if(exclusionFields != null && exclusionFields.length > 0){
                for (String exclusionField : exclusionFields) {
                    beanToMap.remove(exclusionField);
                }
            }


            // 扩展属性 ...
            // 不是外链 则处理组件
            if(!MenuConstants.EXTERNAL.equals(model.getType())){
                beanToMap.put("component", model.getComponent());
            }else{
                // 如果是外链 则判断是否存在 BASE_PATH
                // 设置BASE_PATH
                if(StringUtils.isNotEmpty(model.getUrl())){
                    model.setUrl(model.getUrl().replace("${BASE_PATH}",
                            StartPrint.getInstance().getBasePath()
                    ));
                }
            }

            beanToMap.put(SORT_FIELD, model.getSortNo());
            beanToMap.put("path", model.getUrl());
            beanToMap.put("name", model.getMenuName());

            // 处理 meta
            Map<String,String> metaMap = Maps.newHashMapWithExpectedSize(3);
            metaMap.put("title", model.getMenuName());
            metaMap.put("icon", model.getIcon());
            // 外链处理
            if(MenuConstants.EXTERNAL.equals(model.getType())){
                metaMap.put("target", "_blank");
            }

            beanToMap.put("meta", metaMap);
            beanMapList.add(beanToMap);
        }

        return beanMapList;
    }


    /**
     * 获得菜单树
     * @param menuList 菜单集合
     * @return List
     */
    private List<Tree<Object>> getMenuTrees(List<MenuModel> menuList) {
        //转换器
        return this.getMenuTrees(menuList, null,4 );
    }

    /**
     * 获得菜单树
     * @param menuList 菜单集合
     * @return List
     */
    private List<Tree<Object>> getMenuTrees(List<MenuModel> menuList, String parentId, int deep) {
        //转换器
        return this.getMenuTrees(menuList, null, parentId, deep);
    }

    /**
     * 获得菜单树
     * @param menuList 菜单集合
     * @param exclusionFields 排除字段
     * @return List
     */
    private List<Tree<Object>> getMenuTrees(List<MenuModel> menuList, String[] exclusionFields) {
        return getMenuTrees(menuList, exclusionFields, null, 4);
    }

    /**
     * 获得菜单树
     * @param menuList 菜单集合
     * @param exclusionFields 排除字段
     * @return List
     */
    private List<Tree<Object>> getMenuTrees(List<MenuModel> menuList, String[] exclusionFields,
                                            String parentId,  int deep) {
        if(CollUtil.isEmpty(menuList)){
            return ListUtil.empty();
        }

        // 如果层级等于 0 默认为4层
        if(deep == 0){
            deep = 4;
        }

        // 获得BeanMapList
        List<Map<String, Object>> beanMapList = this.getBeanMapList(menuList, exclusionFields);

        //配置
        TreeNodeConfig treeNodeConfig = new TreeNodeConfig();
        // 自定义属性名 都要默认值的
        treeNodeConfig.setWeightKey(SORT_FIELD);
        // 最大递归深度 最多支持4层菜单
        treeNodeConfig.setDeep(deep);

        // 如果 parentId 不为空
        if(StringUtils.isNotEmpty(parentId)){
            //转换器
            return TreeBuildUtil.INSTANCE.build(beanMapList, parentId ,treeNodeConfig);
        }
        //转换器
        return TreeBuildUtil.INSTANCE.build(beanMapList, treeNodeConfig);
    }

    /**
     * 生成根节点
     * @return MenuModel
     */
    private MenuModel getGenMenuModel() {
        MenuModel model = new MenuModel();
        model.setId(MenuConstants.GEN_ID);
        model.setMenuName("根菜单");
        model.setHidden(DictType.NO_YES_NO.getValue());
        model.setSortNo(-1);
        model.setType(MenuConstants.MENU);
        model.setParentId(VIRTUAL_TOTAL_NODE);
        return model;
    }

    /**
     * 菜单完整 新增
     * @param menuFullModel 模型
     * @return ResultVo
     */
    @Override
    public ResultVo<?> saveMenuByFull(MenuFullModel menuFullModel) {
        IService.saveMenuByFull(menuFullModel);
        return ResultVo.success();
    }
}
