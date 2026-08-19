# -*- coding: utf-8 -*-
"""墨笺端到端冒烟测试：覆盖全部已实现业务域。"""
import json
import urllib.request
import urllib.error

BASE = 'http://localhost:8080/api'
passed = []


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json'} if data else {}
    req = urllib.request.Request(BASE + path, method=method, data=data, headers=headers)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))


def check(name, cond, detail=''):
    assert cond, f'{name} 失败: {detail}'
    passed.append(name)
    print(f'  ✓ {name}')


print('== 健康检查 ==')
check('health', call('GET', '/health')['code'] == 0)

print('== 项目 ==')
proj = call('POST', '/projects', {'name': '冒烟·山河故人', 'genre': '仙侠', 'intro': '一把旧剑与一场大雪'})
check('create project', proj['code'] == 0 and proj['data']['id'], proj)
pid = proj['data']['id']
check('id is string', isinstance(pid, str), pid)

print('== 人物档案 ==')
char = call('POST', f'/projects/{pid}/characters', {'name': '林晚照', 'roleIdentity': '北境剑修', 'personality': '外冷内热'})
check('create character', char['code'] == 0, char)
cid = char['data']['id']
chars = call('GET', f'/projects/{pid}/characters')
check('list characters', chars['code'] == 0 and len(chars['data']) == 1, chars)

print('== 故事设定 ==')
setting = call('POST', f'/projects/{pid}/settings',
               {'title': '剑冢', 'content': '北境雪山深处的万剑埋骨地', 'tags': '地点,禁地', 'triggerKeywords': '剑冢,雪山', 'priority': 5})
check('create setting', setting['code'] == 0, setting)
search = call('GET', f'/projects/{pid}/settings?keyword=' + urllib.request.quote('剑冢'))
check('search setting by keyword', search['code'] == 0 and len(search['data']) == 1, search)
bad_scope = call('POST', f'/projects/{pid}/settings', {'title': '坏范围', 'content': 'x', 'chapterScope': 'abc'})
check('invalid chapter scope rejected', bad_scope['code'] == 40000, bad_scope)

print('== 剧情蓝图 ==')
volume = call('POST', f'/projects/{pid}/outline/nodes', {'nodeType': 'VOLUME', 'name': '第一卷 雪落'})
check('create volume', volume['code'] == 0, volume)
vid = volume['data']['id']
node = call('POST', f'/projects/{pid}/outline/nodes',
            {'nodeType': 'CHAPTER', 'name': '夜雨客栈', 'parentId': vid, 'plotGoal': '主角初到客栈，遭遇第一次刺杀'})
check('create chapter node', node['code'] == 0, node)
nid = node['data']['id']
tree = call('GET', f'/projects/{pid}/outline')
check('tree nesting', tree['code'] == 0 and tree['data'][0]['children'][0]['name'] == '夜雨客栈', tree)

print('== 章节与记忆 ==')
chapter = call('POST', f'/projects/{pid}/chapters', {'title': '第一章 夜雨', 'outlineNodeId': nid})
check('create chapter linked to node', chapter['code'] == 0, chapter)
chid = chapter['data']['id']
upd = call('PUT', f'/chapters/{chid}', {'title': '第一章 夜雨', 'content': '夜雨落了整宿……', 'status': 'WRITING'})
check('manual save chapter', upd['code'] == 0 and upd['data']['status'] == 'WRITING', upd)
mem = call('PUT', f'/chapters/{chid}/memory', {'summary': '主角抵达客栈，遇到黑衣人', 'unresolvedConflicts': '刺杀者身份未明'})
check('save memory', mem['code'] == 0, mem)
mem2 = call('GET', f'/chapters/{chid}/memory')
check('get memory back', mem2['data']['summary'] == '主角抵达客栈，遇到黑衣人', mem2)
chapters = call('GET', f'/projects/{pid}/chapters')
check('chapter list without content', chapters['data'][0].get('content') is None, chapters)

print('== 错误处理 ==')
missing = call('GET', '/projects/999999')
check('missing project 40400', missing['code'] == 40400, missing)
mismatch = call('GET', '/projects/abc')
check('bad path param 40000', mismatch['code'] == 40000, mismatch)

print('== 级联删除 ==')
deleted = call('DELETE', f'/projects/{pid}')
check('delete project', deleted['code'] == 0, deleted)
after = call('GET', f'/projects/{pid}')
check('project gone', after['code'] == 40400, after)
chars_after = call('GET', f'/projects/{pid}/characters')
check('characters gone with project', chars_after['code'] == 40400, chars_after)

print('== 生成链路（Mock 模型）==')
import time
import urllib.request as _ur


def wait_task(task_id, timeout=15):
    for _ in range(timeout * 2):
        body = call('GET', f'/generation-tasks/{task_id}')
        if body['data']['status'] in ('SUCCEEDED', 'FAILED'):
            return body['data']
        time.sleep(0.5)
    raise AssertionError(f'任务 {task_id} 超时未完成')


proj2 = call('POST', '/projects', {'name': '生成冒烟·风雪渡口'})
pid2 = proj2['data']['id']
chapter2 = call('POST', f'/projects/{pid2}/chapters', {'title': '第二章 风雪渡口'})
cid2 = chapter2['data']['id']

plan_task = call('POST', f'/chapters/{cid2}/generation-plans',
                 {'instruction': '主角雪夜渡口遭伏击，旧敌现身'})
plan = wait_task(plan_task['data']['id'])
check('plot plan succeeded', plan['status'] == 'SUCCEEDED', plan)
check('plan result exists', '剧情方案' in (plan.get('result') or ''), (plan.get('result') or '')[:80])
check('context snapshot exists', plan.get('context') is not None, 'missing context')

# SSE：任务已完成，订阅应立即收到最终状态回放
req = _ur.Request(f'{BASE}/generation-tasks/{plan_task["data"]["id"]}/events')
sse_text = ''
try:
    with _ur.urlopen(req, timeout=5) as resp:
        sse_text = resp.read(4096).decode('utf-8')
except Exception:
    sse_text = ''
check('sse replay final status', 'SUCCEEDED' in sse_text, sse_text[:120])

content_task = call('POST', f'/chapters/{cid2}/generation-tasks',
                    {'taskType': 'CONTENT', 'plan': plan['result'], 'instruction': '按方案生成正文'})
content = wait_task(content_task['data']['id'])
check('content succeeded', content['status'] == 'SUCCEEDED', content)
chapter_after = call('GET', f'/chapters/{cid2}')
check('chapter content written', bool(chapter_after['data']['content']), 'content empty')
check('version no advanced', chapter_after['data']['currentVersionNo'] == 1, chapter_after['data'])

memory_task = call('POST', f'/chapters/{cid2}/generation-tasks',
                   {'taskType': 'CHAPTER_MEMORY', 'instruction': '提炼本章记忆'})
memory = wait_task(memory_task['data']['id'])
check('memory draft generated', '摘要' in (memory.get('result') or ''), (memory.get('result') or '')[:80])

clean = call('DELETE', f'/projects/{pid2}')
check('cleanup generation project', clean['code'] == 0, clean)

print(f'\n全部通过: {len(passed)} 项检查')
