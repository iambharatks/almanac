class Solution {
public:
    int totalFruit(vector<int>& fruits) {
        vector<int> count(1e5+1,0);
        int tot = 0;
        int res = 0;
        int l = 0;
        for(int i = 0 ; i < size(fruits) ; i++){
            if(count[fruits[i]] == 0){
                tot++;
            }
            count[fruits[i]]++;
            while(tot > 2){
                count[fruits[l]]--;
                if(count[fruits[l]] == 0)
                    tot--;
                l++;
            }
            if(tot == 2){
                res = max(res,i-l+1);
            }
        }
        if(tot < 2)
            return size(fruits);
        return res;
    }
};